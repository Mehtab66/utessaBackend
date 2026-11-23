package com.crm.crm_backend.billing.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.crm.crm_backend.billing.api.dto.BillingDtos.ChangeResponse;
import com.crm.crm_backend.billing.api.dto.BillingDtos.CheckoutResponse;
import com.crm.crm_backend.billing.api.dto.BillingDtos.CustomerResponse;
import com.crm.crm_backend.billing.api.dto.BillingDtos.SubscriptionResponse;
import com.crm.crm_backend.billing.domain.BillingInterval;
import com.crm.crm_backend.billing.domain.IdempotencyRecord;
import com.crm.crm_backend.billing.domain.OperationStatus;
import com.crm.crm_backend.billing.domain.OrganizationSubscription;
import com.crm.crm_backend.billing.domain.SubscriptionItem;
import com.crm.crm_backend.billing.domain.SubscriptionItemStatus;
import com.crm.crm_backend.billing.infrastructure.IdempotencyRecordRepository;
import com.crm.crm_backend.billing.infrastructure.OrganizationSubscriptionRepository;
import com.crm.crm_backend.billing.infrastructure.SubscriptionItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Customer;
import com.stripe.model.checkout.Session;

/**
 * Settles billing operations whose outcome is unknown (Stripe timeout, server stopped mid-call).
 * It never assumes failure: it reads the real state from Stripe and records SUCCEEDED when the
 * requested change is there, FAILED when it demonstrably isn't. Until then the operation stays
 * UNKNOWN, and a retry with the same Idempotency-Key resumes it with the same Stripe keys.
 */
@Service
public class BillingReconciler {

	private static final Logger log = LoggerFactory.getLogger(BillingReconciler.class);
	/** Give Stripe this long to finish before judging an operation. */
	static final Duration SETTLE_AFTER = Duration.ofSeconds(90);
	/** After this, Stripe's idempotency window has closed; an unverifiable operation is closed as failed. */
	static final Duration GIVE_UP_AFTER = Duration.ofHours(23);
	private static final String NOT_APPLIED = "This billing change didn't go through in Stripe. Nothing was charged for it — please make the change again.";

	public enum Result { SUCCEEDED, FAILED, STILL_UNKNOWN, SKIPPED }

	private final IdempotencyRecordRepository records;
	private final IdempotencyService idempotency;
	private final StripeGateway gateway;
	private final SubscriptionSyncService sync;
	private final OrganizationSubscriptionRepository subscriptions;
	private final SubscriptionItemRepository items;
	private final BillingQueryService queries;
	private final ObjectMapper json;

	public BillingReconciler(IdempotencyRecordRepository records, IdempotencyService idempotency, StripeGateway gateway,
			SubscriptionSyncService sync, OrganizationSubscriptionRepository subscriptions, SubscriptionItemRepository items,
			BillingQueryService queries, ObjectMapper json) {
		this.records = records;
		this.idempotency = idempotency;
		this.gateway = gateway;
		this.sync = sync;
		this.subscriptions = subscriptions;
		this.items = items;
		this.queries = queries;
		this.json = json;
	}

	/** UNKNOWN operations, and PROCESSING ones whose lease ran out (the server stopped mid-call). */
	public List<IdempotencyRecord> candidates() {
		Instant now = Instant.now();
		return records.findByProcessingStatusInAndUpdatedAtBefore(
						List.of(OperationStatus.UNKNOWN, OperationStatus.PROCESSING, OperationStatus.PENDING), now.minus(SETTLE_AFTER))
				.stream()
				.filter(r -> r.getProcessingStatus() == OperationStatus.UNKNOWN
						|| r.getLockedUntil() == null || r.getLockedUntil().isBefore(now))
				.toList();
	}

	public Result reconcile(IdempotencyRecord r) {
		try {
			Result result = switch (r.getOperationType()) {
				case CREATE_CUSTOMER -> customerCreated(r);
				case UPDATE_CUSTOMER -> customerUpdated(r);
				case CREATE_SUBSCRIPTION -> checkoutCreated(r);
				case UPGRADE, DOWNGRADE, CHANGE_SEATS, CHANGE_INTERVAL -> planChanged(r);
				case CANCEL_SCHEDULED_CHANGE -> scheduleReleased(r);
				case CANCEL, REACTIVATE -> cancellationChanged(r);
				default -> Result.SKIPPED;
			};
			if ((result == Result.STILL_UNKNOWN || result == Result.SKIPPED)
					&& r.getCreatedAt().isBefore(Instant.now().minus(GIVE_UP_AFTER))) {
				idempotency.settle(r.getId(), OperationStatus.FAILED, null, null,
						"This billing change couldn't be confirmed with Stripe in time. Check your subscription and make the change again if needed.");
				log.warn("Billing operation {} {} closed as FAILED after {}h unresolved", r.getOperationType(), r.getId(), GIVE_UP_AFTER.toHours());
				return Result.FAILED;
			}
			return result;
		} catch (StripeCallException ex) {
			log.warn("Couldn't reconcile billing operation {} {} yet: {}", r.getOperationType(), r.getId(), ex.getMessage());
			return Result.STILL_UNKNOWN;
		}
	}

	// ── per operation ───────────────────────────────────────────────────

	private Result customerCreated(IdempotencyRecord r) {
		UUID orgId = r.getOrganizationId();
		Customer c = gateway.findCustomerForOrganization(orgId).orElse(null);
		if (c == null) {
			return settleFailed(r);
		}
		sync.ensureRow(orgId, c.getId());
		subscriptions.findByOrganizationId(orgId).filter(row -> row.getStripeCustomerId() == null).ifPresent(row -> {
			row.setStripeCustomerId(c.getId());
			subscriptions.save(row);
		});
		return settle(r, c.getId(), new CustomerResponse(true, c.getName(), c.getEmail(), c.getPhone(), null));
	}

	private Result customerUpdated(IdempotencyRecord r) {
		String customerId = subscriptions.findByOrganizationId(r.getOrganizationId())
				.map(OrganizationSubscription::getStripeCustomerId).orElse(null);
		if (customerId == null) {
			return settleFailed(r);
		}
		Customer c = gateway.retrieveCustomer(customerId);
		JsonNode req = request(r);
		boolean matches = same(req.path("name"), c.getName()) && same(req.path("email"), c.getEmail()) && same(req.path("phone"), c.getPhone());
		return matches ? settle(r, customerId, new CustomerResponse(true, c.getName(), c.getEmail(), c.getPhone(), null)) : settleFailed(r);
	}

	private Result checkoutCreated(IdempotencyRecord r) {
		UUID orgId = r.getOrganizationId();
		String customerId = subscriptions.findByOrganizationId(orgId).map(OrganizationSubscription::getStripeCustomerId).orElse(null);
		if (customerId == null) {
			return settleFailed(r);
		}
		long since = r.getCreatedAt().minusSeconds(60).getEpochSecond();
		Session s = gateway.listCheckoutSessions(customerId).stream()
				.filter(x -> orgId.toString().equals(x.getClientReferenceId()))
				.filter(x -> x.getCreated() != null && x.getCreated() >= since)
				.max(Comparator.comparing(Session::getCreated))
				.orElse(null);
		if (s == null) {
			return settleFailed(r);
		}
		if ("open".equals(s.getStatus())) {
			subscriptions.findByOrganizationId(orgId).ifPresent(row -> {
				row.setPendingCheckoutSessionId(s.getId());
				row.setPendingCheckoutExpiresAt(SubscriptionSyncService.instant(s.getExpiresAt()));
				subscriptions.save(row);
			});
		} else if ("complete".equals(s.getStatus()) && s.getSubscription() != null) {
			sync.syncById(s.getSubscription());
		}
		return settle(r, s.getId(), new CheckoutResponse(s.getId(), s.getUrl(), SubscriptionSyncService.instant(s.getExpiresAt()), null));
	}

	/** Compares the requested modules / seats / interval with what Stripe has now or has booked. */
	private Result planChanged(IdempotencyRecord r) {
		OrganizationSubscription row = subscriptions.findByOrganizationId(r.getOrganizationId()).orElse(null);
		if (row == null || row.getStripeSubscriptionId() == null) {
			return settleFailed(r);
		}
		if (r.getRequestBody() == null) {
			// Recorded before requests were stored: can't be verified, so it waits for the give-up rule.
			return Result.STILL_UNKNOWN;
		}
		sync.syncById(row.getStripeSubscriptionId());
		row = subscriptions.findByOrganizationId(r.getOrganizationId()).orElseThrow();
		JsonNode req = request(r);
		Set<String> wantModules = req.path("modules").isArray() ? strings(req.path("modules")) : null;
		Integer wantSeats = req.path("seats").isNumber() ? req.path("seats").asInt() : null;
		BillingInterval wantInterval = req.path("interval").isTextual() ? BillingInterval.valueOf(req.path("interval").asText()) : null;

		List<SubscriptionItem> all = items.findBySubscriptionId(row.getId());
		// Now: ACTIVE + SCHEDULED_REMOVAL (still running until the renewal).
		Set<String> nowModules = new TreeSet<>();
		int nowSeats = 0;
		// At renewal: what stays + what's booked to be added.
		Set<String> nextModules = new TreeSet<>();
		int activeSeats = 0;
		Integer bookedSeats = null;
		for (SubscriptionItem i : all) {
			switch (i.getStatus()) {
				case ACTIVE -> {
					if (i.isSeat()) {
						nowSeats = i.getQuantity();
						activeSeats = i.getQuantity();
					} else {
						nowModules.add(i.getModuleKey());
						nextModules.add(i.getModuleKey());
					}
				}
				case SCHEDULED_REMOVAL -> {
					if (i.isSeat()) {
						nowSeats = i.getQuantity();
						bookedSeats = bookedSeats == null ? 0 : bookedSeats;
					} else {
						nowModules.add(i.getModuleKey());
					}
				}
				case SCHEDULED_ADD -> {
					if (i.isSeat()) {
						bookedSeats = i.getQuantity();
					} else {
						nextModules.add(i.getModuleKey());
					}
				}
				default -> {
					// REMOVED: history only
				}
			}
		}
		int nextSeats = bookedSeats != null ? bookedSeats : activeSeats;
		BillingInterval nowInterval = row.getBillingInterval();
		BillingInterval nextInterval = row.getScheduledInterval() != null ? row.getScheduledInterval() : nowInterval;

		boolean appliedNow = matches(wantModules, nowModules, wantSeats, nowSeats, wantInterval, nowInterval);
		boolean booked = row.getScheduledChangeAt() != null
				&& matches(wantModules, nextModules, wantSeats, nextSeats, wantInterval, nextInterval);
		SubscriptionResponse summary = queries.subscription(r.getOrganizationId());
		if (appliedNow) {
			return settle(r, row.getStripeSubscriptionId(), new ChangeResponse("APPLIED", r.getCreatedAt(), null, null, summary));
		}
		if (booked) {
			return settle(r, row.getStripeSubscriptionId(), new ChangeResponse("SCHEDULED", row.getScheduledChangeAt(), null, null, summary));
		}
		return settleFailed(r);
	}

	private Result scheduleReleased(IdempotencyRecord r) {
		OrganizationSubscription row = subscriptions.findByOrganizationId(r.getOrganizationId()).orElse(null);
		if (row == null || row.getStripeSubscriptionId() == null) {
			return settleFailed(r);
		}
		sync.syncById(row.getStripeSubscriptionId());
		row = subscriptions.findByOrganizationId(r.getOrganizationId()).orElseThrow();
		if (row.getStripeScheduleId() == null) {
			return settle(r, row.getStripeSubscriptionId(),
					new ChangeResponse("APPLIED", Instant.now(), null, null, queries.subscription(r.getOrganizationId())));
		}
		return settleFailed(r);
	}

	private Result cancellationChanged(IdempotencyRecord r) {
		OrganizationSubscription row = subscriptions.findByOrganizationId(r.getOrganizationId()).orElse(null);
		if (row == null || row.getStripeSubscriptionId() == null) {
			return settleFailed(r);
		}
		sync.syncById(row.getStripeSubscriptionId());
		row = subscriptions.findByOrganizationId(r.getOrganizationId()).orElseThrow();
		JsonNode req = request(r);
		boolean ok;
		String result;
		if (r.getOperationType() == com.crm.crm_backend.billing.domain.OperationType.REACTIVATE) {
			ok = !row.isCancelAtPeriodEnd() && !SubscriptionSyncService.ENDED.contains(row.getSubscriptionStatus());
			result = "REACTIVATED";
		} else if ("IMMEDIATE".equals(req.path("mode").asText())) {
			ok = "canceled".equals(row.getSubscriptionStatus());
			result = "CANCELED";
		} else {
			ok = row.isCancelAtPeriodEnd();
			result = "CANCEL_SCHEDULED";
		}
		return ok ? settle(r, row.getStripeSubscriptionId(), new ChangeResponse(result, Instant.now(), null, null,
				queries.subscription(r.getOrganizationId()))) : settleFailed(r);
	}

	// ── helpers ─────────────────────────────────────────────────────────

	private static boolean matches(Set<String> wantModules, Set<String> modules, Integer wantSeats, int seats,
			BillingInterval wantInterval, BillingInterval interval) {
		return (wantModules == null || wantModules.equals(modules))
				&& (wantSeats == null || wantSeats == seats)
				&& (wantInterval == null || wantInterval == interval);
	}

	private Result settle(IdempotencyRecord r, String reference, Object response) {
		boolean done = idempotency.settle(r.getId(), OperationStatus.SUCCEEDED, reference, response, null);
		if (done) {
			log.info("Billing operation {} {} settled as SUCCEEDED (verified in Stripe)", r.getOperationType(), r.getId());
		}
		return done ? Result.SUCCEEDED : Result.SKIPPED;
	}

	private Result settleFailed(IdempotencyRecord r) {
		boolean done = idempotency.settle(r.getId(), OperationStatus.FAILED, null, null, NOT_APPLIED);
		if (done) {
			log.info("Billing operation {} {} settled as FAILED (not found in Stripe)", r.getOperationType(), r.getId());
		}
		return done ? Result.FAILED : Result.SKIPPED;
	}

	private JsonNode request(IdempotencyRecord r) {
		try {
			return r.getRequestBody() == null ? json.createObjectNode() : json.readTree(r.getRequestBody());
		} catch (Exception ex) {
			return json.createObjectNode();
		}
	}

	private static Set<String> strings(JsonNode array) {
		Set<String> out = new TreeSet<>();
		array.forEach(n -> out.add(n.asText()));
		return out;
	}

	private static boolean same(JsonNode wanted, String actual) {
		return wanted == null || wanted.isNull() || wanted.isMissingNode() || wanted.asText().trim().equals(actual);
	}
}
