package com.crm.crm_backend.billing.application;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.crm.crm_backend.billing.domain.OrganizationSubscription;
import com.crm.crm_backend.billing.domain.StripeWebhookEvent;
import com.crm.crm_backend.billing.domain.WebhookProcessingStatus;
import com.crm.crm_backend.billing.infrastructure.OrganizationSubscriptionRepository;
import com.crm.crm_backend.billing.infrastructure.StripeWebhookEventRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;

/**
 * Receives Stripe webhooks.
 * <ol>
 * <li>Verifies the signature with Stripe's library (and its 5-minute replay window); anything
 * unsigned or tampered is rejected before it's even stored.</li>
 * <li>Records the event id once (unique constraint): a redelivered event is acknowledged and
 * skipped, so processing is idempotent.</li>
 * <li>Handles it by re-reading the subscription / invoice / session from Stripe — the event's own
 * copy is never trusted — so events arriving late or out of order can't roll state back.</li>
 * <li>On error the event is marked FAILED and a 500 makes Stripe retry; the retry job also
 * re-processes it.</li>
 * </ol>
 */
@Service
public class StripeWebhookService {

	private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);
	/** Another delivery of the same event being processed right now is left alone this long. */
	private static final Duration IN_FLIGHT = Duration.ofMinutes(2);

	static final Set<String> HANDLED = Set.of(
			"customer.subscription.created",
			"customer.subscription.updated",
			"customer.subscription.deleted",
			"customer.subscription.trial_will_end",
			"invoice.paid",
			"invoice.payment_failed",
			"checkout.session.completed");

	public enum Outcome { PROCESSED, DUPLICATE, IGNORED }

	/** Thrown for a missing or wrong signature (→ 400). */
	public static class InvalidSignatureException extends RuntimeException {
		public InvalidSignatureException(String message) {
			super(message);
		}
	}

	private record Handled(WebhookProcessingStatus status, UUID organizationId) {
	}

	private final StripeProperties props;
	private final StripeGateway gateway;
	private final StripeWebhookEventRepository events;
	private final OrganizationSubscriptionRepository subscriptions;
	private final SubscriptionSyncService sync;
	private final InvoiceSyncService invoiceSync;
	private final ApplicationEventPublisher publisher;
	private final EntitlementService entitlements;
	private final ObjectMapper json;
	private final TransactionTemplate tx;

	public StripeWebhookService(StripeProperties props, StripeGateway gateway, StripeWebhookEventRepository events,
			OrganizationSubscriptionRepository subscriptions, SubscriptionSyncService sync, InvoiceSyncService invoiceSync,
			ApplicationEventPublisher publisher, EntitlementService entitlements, ObjectMapper json, PlatformTransactionManager txManager) {
		this.props = props;
		this.gateway = gateway;
		this.events = events;
		this.subscriptions = subscriptions;
		this.sync = sync;
		this.invoiceSync = invoiceSync;
		this.publisher = publisher;
		this.entitlements = entitlements;
		this.json = json;
		this.tx = new TransactionTemplate(txManager);
	}

	/** Entry point for POST /api/v1/stripe/webhook. */
	public Outcome receive(byte[] body, String signature) {
		if (!props.webhookConfigured()) {
			// 503 makes Stripe retry later, once the signing secret has been configured.
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Webhook signing secret is not configured.");
		}
		if (signature == null || signature.isBlank()) {
			throw new InvalidSignatureException("Missing Stripe-Signature header");
		}
		Event event;
		try {
			event = Webhook.constructEvent(new String(body, StandardCharsets.UTF_8), signature, props.webhookSecret());
		} catch (SignatureVerificationException ex) {
			throw new InvalidSignatureException("Signature verification failed");
		} catch (RuntimeException ex) {
			throw new InvalidSignatureException("Payload could not be parsed");
		}
		return handle(event);
	}

	/** Processes a verified event (also used by the retry job with events re-read from Stripe). */
	public Outcome handle(Event event) {
		boolean liveKey = props.secretKey() != null && props.secretKey().startsWith("sk_live_");
		boolean liveEvent = Boolean.TRUE.equals(event.getLivemode());
		JsonNode object = dataObject(event);
		String objectId = object.path("id").asText(null);

		StripeWebhookEvent row = claim(event, objectId);
		if (row == null) {
			return Outcome.DUPLICATE;
		}
		if (liveKey != liveEvent) {
			finish(row.getId(), WebhookProcessingStatus.IGNORED, null, "Event mode doesn't match the API key mode");
			return Outcome.IGNORED;
		}
		if (!HANDLED.contains(event.getType())) {
			finish(row.getId(), WebhookProcessingStatus.IGNORED, null, null);
			return Outcome.IGNORED;
		}
		try {
			Handled h = dispatch(event.getType(), objectId);
			finish(row.getId(), h.status(), h.organizationId(), null);
			log.info("Stripe event {} {} → {}", event.getType(), event.getId(), h.status());
			return h.status() == WebhookProcessingStatus.IGNORED ? Outcome.IGNORED : Outcome.PROCESSED;
		} catch (RuntimeException ex) {
			finish(row.getId(), WebhookProcessingStatus.FAILED, null, truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
			log.error("Stripe event {} {} failed; Stripe and the retry job will try again", event.getType(), event.getId(), ex);
			throw ex;
		}
	}

	// ── handlers ───────────────────────────────────────────────────────

	private Handled dispatch(String type, String objectId) {
		if (objectId == null) {
			return new Handled(WebhookProcessingStatus.IGNORED, null);
		}
		return switch (type) {
			case "customer.subscription.created", "customer.subscription.updated" -> subscriptionChanged(objectId, false);
			case "customer.subscription.deleted" -> subscriptionChanged(objectId, true);
			case "customer.subscription.trial_will_end" -> trialWillEnd(objectId);
			case "invoice.paid" -> invoicePaid(objectId);
			case "invoice.payment_failed" -> invoiceFailed(objectId);
			case "checkout.session.completed" -> checkoutCompleted(objectId);
			default -> new Handled(WebhookProcessingStatus.IGNORED, null);
		};
	}

	private Handled subscriptionChanged(String subscriptionId, boolean deleted) {
		Subscription s = gateway.retrieveSubscription(subscriptionId);
		SubscriptionSyncService.Outcome outcome = sync.sync(s);
		UUID orgId = sync.resolveOrganization(s).orElse(null);
		if (outcome == SubscriptionSyncService.Outcome.UNMATCHED) {
			return new Handled(WebhookProcessingStatus.IGNORED, null);
		}
		if (outcome == SubscriptionSyncService.Outcome.DUPLICATE) {
			cancelDuplicate(s, orgId);
			return new Handled(WebhookProcessingStatus.PROCESSED, orgId);
		}
		if (deleted && "canceled".equals(s.getStatus()) && isCurrent(orgId, s.getId())) {
			publish(new BillingNotification(BillingNotification.Type.SUBSCRIPTION_CANCELED, orgId,
					SubscriptionSyncService.instant(s.getEndedAt()), null, null, null, false));
		}
		return new Handled(WebhookProcessingStatus.PROCESSED, orgId);
	}

	private Handled trialWillEnd(String subscriptionId) {
		Subscription s = gateway.retrieveSubscription(subscriptionId);
		if (sync.sync(s) != SubscriptionSyncService.Outcome.APPLIED) {
			return new Handled(WebhookProcessingStatus.IGNORED, null);
		}
		UUID orgId = sync.resolveOrganization(s).orElse(null);
		boolean hasCard = s.getDefaultPaymentMethod() != null;
		if (!hasCard && s.getCustomer() != null) {
			Customer c = gateway.retrieveCustomer(s.getCustomer());
			hasCard = c.getInvoiceSettings() != null && c.getInvoiceSettings().getDefaultPaymentMethod() != null;
		}
		publish(new BillingNotification(BillingNotification.Type.TRIAL_ENDING, orgId,
				SubscriptionSyncService.instant(s.getTrialEnd()), null, null, null, hasCard));
		return new Handled(WebhookProcessingStatus.PROCESSED, orgId);
	}

	private Handled invoicePaid(String invoiceId) {
		Invoice inv = gateway.retrieveInvoice(invoiceId);
		OrganizationSubscription before = invoiceSync.owner(inv).orElse(null);
		if (before == null) {
			return new Handled(WebhookProcessingStatus.IGNORED, null);
		}
		boolean wasFailing = before.getPaymentFailedAt() != null;
		invoiceSync.upsert(inv);
		String subId = subscriptionOf(inv);
		if (subId != null) {
			sync.syncById(subId);
		}
		OrganizationSubscription after = subscriptions.findByOrganizationId(before.getOrganizationId()).orElseThrow();
		tx.executeWithoutResult(s -> subscriptions.lockByOrganizationId(after.getOrganizationId()).ifPresent(row -> {
			row.setLatestInvoiceStatus(inv.getStatus());
			subscriptions.save(row);
		}));
		if (wasFailing && after.getPaymentFailedAt() == null) {
			publish(new BillingNotification(BillingNotification.Type.PAYMENT_RECOVERED, after.getOrganizationId(),
					Instant.now(), inv.getAmountPaid(), inv.getCurrency(), null, false));
		}
		return new Handled(WebhookProcessingStatus.PROCESSED, before.getOrganizationId());
	}

	/**
	 * A failed renewal (subscription now past_due/unpaid) starts the grace period; access is
	 * restricted only after it ends, and data is never deleted. A failed upgrade payment just
	 * means the change wasn't applied (pending update) — no grace period, the plan is unchanged.
	 */
	private Handled invoiceFailed(String invoiceId) {
		Invoice inv = gateway.retrieveInvoice(invoiceId);
		OrganizationSubscription owner = invoiceSync.owner(inv).orElse(null);
		if (owner == null) {
			return new Handled(WebhookProcessingStatus.IGNORED, null);
		}
		invoiceSync.upsert(inv);
		String subId = subscriptionOf(inv);
		if (subId != null) {
			sync.syncById(subId);
		}
		UUID orgId = owner.getOrganizationId();
		Instant[] graceEnd = new Instant[1];
		boolean[] renewalFailure = new boolean[1];
		tx.executeWithoutResult(s -> subscriptions.lockByOrganizationId(orgId).ifPresent(row -> {
			row.setLatestInvoiceId(inv.getId());
			row.setLatestInvoiceStatus(inv.getStatus());
			String status = row.getSubscriptionStatus();
			if ("past_due".equals(status) || "unpaid".equals(status)) {
				renewalFailure[0] = true;
				if (row.getPaymentFailedAt() == null) {
					Instant failedAt = Instant.now();
					row.setPaymentFailedAt(failedAt);
					row.setGracePeriodEndsAt(failedAt.plus(Duration.ofDays(props.gracePeriodDays())));
				}
				graceEnd[0] = row.getGracePeriodEndsAt();
			}
			subscriptions.save(row);
		}));
		entitlements.evict(orgId);
		BillingNotification.Type type = renewalFailure[0] ? BillingNotification.Type.PAYMENT_FAILED
				: "subscription_update".equals(inv.getBillingReason()) ? BillingNotification.Type.CHANGE_PAYMENT_FAILED
				: BillingNotification.Type.PAYMENT_FAILED;
		publish(new BillingNotification(type, orgId, graceEnd[0], inv.getAmountRemaining(), inv.getCurrency(),
				inv.getHostedInvoiceUrl(), false));
		return new Handled(WebhookProcessingStatus.PROCESSED, orgId);
	}

	private Handled checkoutCompleted(String sessionId) {
		Session session = gateway.retrieveCheckoutSession(sessionId);
		String ref = session.getClientReferenceId() != null ? session.getClientReferenceId()
				: Optional.ofNullable(session.getMetadata()).map(m -> m.get(StripeGateway.ORG_METADATA)).orElse(null);
		UUID orgId;
		try {
			orgId = ref == null ? null : UUID.fromString(ref);
		} catch (IllegalArgumentException ex) {
			orgId = null;
		}
		if (orgId == null || !"subscription".equals(session.getMode()) || session.getSubscription() == null) {
			return new Handled(WebhookProcessingStatus.IGNORED, orgId);
		}
		Subscription s = gateway.retrieveSubscription(session.getSubscription());
		SubscriptionSyncService.Outcome outcome = sync.sync(s);
		if (outcome == SubscriptionSyncService.Outcome.DUPLICATE) {
			cancelDuplicate(s, orgId);
		}
		return new Handled(WebhookProcessingStatus.PROCESSED, orgId);
	}

	// ── helpers ─────────────────────────────────────────────────────────

	/**
	 * A second live subscription for an organization that already has one (should be impossible:
	 * checkouts are expired before new ones start). Cancel it at once so it never bills again.
	 */
	private void cancelDuplicate(Subscription dup, UUID orgId) {
		gateway.cancelSubscriptionNow(dup.getId(), "utessa-cancel-duplicate-" + dup.getId());
		log.error("Cancelled duplicate subscription {} for organization {}. Review in Stripe whether a refund is due.",
				dup.getId(), orgId);
	}

	private boolean isCurrent(UUID orgId, String subscriptionId) {
		return orgId != null && subscriptions.findByOrganizationId(orgId)
				.map(r -> subscriptionId.equals(r.getStripeSubscriptionId())).orElse(false);
	}

	private static String subscriptionOf(Invoice inv) {
		return inv.getParent() != null && inv.getParent().getSubscriptionDetails() != null
				? inv.getParent().getSubscriptionDetails().getSubscription() : null;
	}

	private void publish(BillingNotification n) {
		if (n.organizationId() != null) {
			publisher.publishEvent(n);
		}
	}

	/** Records the event, or returns null when it was already handled / is being handled right now. */
	private StripeWebhookEvent claim(Event event, String objectId) {
		try {
			return tx.execute(s -> {
				StripeWebhookEvent row = events.findByStripeEventId(event.getId()).orElse(null);
				Instant now = Instant.now();
				if (row == null) {
					row = new StripeWebhookEvent();
					row.setStripeEventId(event.getId());
					row.setEventType(event.getType());
					row.setStripeObjectId(objectId);
					row.setLivemode(Boolean.TRUE.equals(event.getLivemode()));
					row.setStripeCreatedAt(SubscriptionSyncService.instant(event.getCreated()));
				} else if (row.getProcessingStatus() == WebhookProcessingStatus.PROCESSED
						|| row.getProcessingStatus() == WebhookProcessingStatus.IGNORED) {
					return null;
				} else if (row.getProcessingStatus() == WebhookProcessingStatus.PROCESSING
						&& row.getUpdatedAt() != null && row.getUpdatedAt().isAfter(now.minus(IN_FLIGHT))) {
					return null;
				}
				row.setProcessingStatus(WebhookProcessingStatus.PROCESSING);
				row.setAttempts(row.getAttempts() + 1);
				row.setErrorMessage(null);
				return events.saveAndFlush(row);
			});
		} catch (DataIntegrityViolationException concurrentDelivery) {
			// The same event arrived twice at the same moment; the other delivery is handling it.
			return null;
		}
	}

	private void finish(UUID rowId, WebhookProcessingStatus status, UUID orgId, String error) {
		tx.executeWithoutResult(s -> events.findById(rowId).ifPresent(r -> {
			r.setProcessingStatus(status);
			if (orgId != null) {
				r.setOrganizationId(orgId);
			}
			r.setErrorMessage(error);
			if (status != WebhookProcessingStatus.FAILED) {
				r.setProcessedAt(Instant.now());
			}
			events.save(r);
		}));
	}

	private JsonNode dataObject(Event event) {
		try {
			String raw = event.getDataObjectDeserializer().getRawJson();
			return raw == null ? json.createObjectNode() : json.readTree(raw);
		} catch (Exception ex) {
			return json.createObjectNode();
		}
	}

	private static String truncate(String s) {
		return s == null || s.length() <= 1000 ? s : s.substring(0, 1000);
	}
}
