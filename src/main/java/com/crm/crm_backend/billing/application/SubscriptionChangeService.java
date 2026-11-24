package com.crm.crm_backend.billing.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.crm.crm_backend.billing.api.dto.BillingDtos.ChangeRequest;
import com.crm.crm_backend.billing.api.dto.BillingDtos.ChangeResponse;
import com.crm.crm_backend.billing.api.dto.BillingDtos.IntervalRequest;
import com.crm.crm_backend.billing.api.dto.BillingDtos.Money;
import com.crm.crm_backend.billing.api.dto.BillingDtos.PreviewLine;
import com.crm.crm_backend.billing.api.dto.BillingDtos.PreviewResponse;
import com.crm.crm_backend.billing.api.dto.BillingDtos.SeatsRequest;
import com.crm.crm_backend.billing.api.dto.BillingDtos.SubscriptionResponse;
import com.crm.crm_backend.billing.application.ChangePlanner.Current;
import com.crm.crm_backend.billing.application.ChangePlanner.ItemChange;
import com.crm.crm_backend.billing.application.ChangePlanner.Kind;
import com.crm.crm_backend.billing.application.ChangePlanner.Line;
import com.crm.crm_backend.billing.application.ChangePlanner.Plan;
import com.crm.crm_backend.billing.application.ChangePlanner.Target;
import com.crm.crm_backend.billing.domain.BillingInterval;
import com.crm.crm_backend.billing.domain.BillingModule;
import com.crm.crm_backend.billing.domain.ChangeTiming;
import com.crm.crm_backend.billing.domain.OperationType;
import com.crm.crm_backend.billing.domain.OrganizationSubscription;
import com.crm.crm_backend.billing.domain.SubscriptionItem;
import com.crm.crm_backend.billing.infrastructure.OrganizationSubscriptionRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionSchedule;
import com.stripe.param.InvoiceCreatePreviewParams;
import com.stripe.param.SubscriptionScheduleUpdateParams;
import com.stripe.param.SubscriptionUpdateParams;

/**
 * Changes the organization's ONE existing Stripe subscription: add/remove modules, change seats,
 * switch monthly ↔ yearly — now (with prorations) or at the next renewal (subscription schedule).
 *
 * <p>Rules:
 * <ul>
 * <li>Items are updated by their Stripe item id; a new subscription is never created here.</li>
 * <li>Anything that charges money now needs a fresh preview token, and is sent with the token's
 * proration date, so the customer pays exactly what the preview showed.</li>
 * <li>Charging changes use {@code payment_behavior=pending_if_incomplete}: if the payment fails,
 * Stripe does not apply the change, and the local records (synced from Stripe) don't either.</li>
 * <li>During the free trial every change is immediate and free (nothing is invoiced).</li>
 * </ul>
 */
@Service
public class SubscriptionChangeService {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionChangeService.class);
	private static final Set<String> CHANGEABLE = Set.of("trialing", "active", "past_due");

	enum Endpoint {
		UPGRADE(OperationType.UPGRADE),
		DOWNGRADE(OperationType.DOWNGRADE),
		SEATS(OperationType.CHANGE_SEATS),
		INTERVAL(OperationType.CHANGE_INTERVAL);

		final OperationType operation;

		Endpoint(OperationType operation) {
			this.operation = operation;
		}
	}

	/**
	 * Current subscription, freshly read from Stripe. {@code booked} / {@code bookedInterval} are the
	 * plan already booked for the next renewal (the schedule's next phase), or null when none is.
	 */
	record Ctx(UUID orgId, OrganizationSubscription row, Subscription stripeSub, List<Current> current,
			BillingInterval interval, boolean inTrial, String stateHash, List<Current> booked, BillingInterval bookedInterval) {

		/** What a change starts from: the plan booked for renewal if there is one, else the current plan. */
		List<Current> base() {
			return booked != null ? booked : current;
		}

		BillingInterval baseInterval() {
			return booked != null ? bookedInterval : interval;
		}
	}

	/**
	 * The request as sent, fingerprinted for idempotency (modules normalized and sorted; the
	 * preview token is deliberately left out so a retry with a re-issued token still matches).
	 */
	record NormalizedChange(String endpoint, List<String> modules, Integer seats, String interval, String timing) {
	}

	private final BillingAccess access;
	private final PricingCatalog catalog;
	private final StripeGateway gateway;
	private final IdempotencyService idempotency;
	private final SubscriptionSyncService sync;
	private final OrganizationSubscriptionRepository subscriptions;
	private final BillingQueryService queries;
	private final PreviewTokens tokens;
	private final InvoiceSyncService invoiceSync;

	public SubscriptionChangeService(BillingAccess access, PricingCatalog catalog, StripeGateway gateway,
			IdempotencyService idempotency, SubscriptionSyncService sync, OrganizationSubscriptionRepository subscriptions,
			BillingQueryService queries, PreviewTokens tokens, InvoiceSyncService invoiceSync) {
		this.access = access;
		this.catalog = catalog;
		this.gateway = gateway;
		this.idempotency = idempotency;
		this.sync = sync;
		this.subscriptions = subscriptions;
		this.queries = queries;
		this.tokens = tokens;
		this.invoiceSync = invoiceSync;
	}

	// ── preview ─────────────────────────────────────────────────────────

	/** POST /billing/preview — what the change costs now and at the next invoice. Changes nothing. */
	public PreviewResponse preview(UserPrincipal principal, ChangeRequest request) {
		UUID orgId = access.requireBillingAdmin(principal);
		Ctx ctx = load(orgId);
		Plan plan = plan(ctx, request.modules(), request.seats(), request.interval());
		requireSingleKind(plan, ctx.booked() != null);
		ChangeTiming timing = timing(plan, ctx, request.timing());
		requireNoBookingForImmediate(ctx, timing);
		boolean charges = chargesNow(plan, timing, ctx);
		long prorationDate = gateway.nowFor(ctx.stripeSub());
		String currency = catalog.currency();

		Money dueNow = new Money(0, currency);
		Money next;
		Instant nextAt;
		Instant effectiveAt;
		List<PreviewLine> lines;

		if (ctx.inTrial()) {
			effectiveAt = Instant.now();
			next = new Money(plan.targetTotal(), currency);
			nextAt = ctx.row().getTrialEnd();
			lines = targetLines(plan);
		} else if (timing == ChangeTiming.IMMEDIATE) {
			Invoice inv = gateway.previewInvoice(previewParams(ctx, plan, charges, prorationDate));
			effectiveAt = Instant.ofEpochSecond(prorationDate);
			Set<String> pending = gateway.pendingInvoiceItemIds(ctx.stripeSub().getCustomer());
			lines = invoiceLines(inv, pending);
			if (charges) {
				// Stripe's immediate invoice holds only this change's lines; credits already pending
				// from earlier changes stay on the next regular invoice. Count exactly what's charged.
				long changeTotal = inv.getLines() == null ? 0 : inv.getLines().getData().stream()
						.filter(l -> isChangeLine(l, pending))
						.mapToLong(l -> nz(l.getAmount())).sum();
				long due = Math.max(0, changeTotal + nz(inv.getStartingBalance()));
				dueNow = new Money(due, inv.getCurrency());
				next = new Money(plan.targetTotal(), currency);
				nextAt = plan.kind() == Kind.INTERVAL
						? effectiveAt.atZone(java.time.ZoneOffset.UTC).plus(1, plan.target().interval() == BillingInterval.YEARLY ? ChronoUnit.YEARS : ChronoUnit.MONTHS).toInstant()
						: ctx.row().getCurrentPeriodEnd();
			} else {
				// Immediate reduction: unused time is credited to the next invoice.
				next = new Money(nz(inv.getAmountDue()), inv.getCurrency());
				nextAt = ctx.row().getCurrentPeriodEnd();
			}
		} else {
			effectiveAt = ctx.row().getCurrentPeriodEnd();
			next = new Money(plan.targetTotal(), currency);
			nextAt = ctx.row().getCurrentPeriodEnd();
			lines = targetLines(plan);
		}

		Instant expires = Instant.now().plus(PreviewTokens.TTL);
		String token = tokens.issue(orgId, changeHash(plan, timing), ctx.stateHash(), prorationDate, expires);
		return new PreviewResponse(plan.kind().name(), timing, effectiveAt, dueNow, next, nextAt, lines,
				List.copyOf(plan.addedModules()), List.copyOf(plan.removedModules()), plan.seatsFrom(), plan.seatsTo(),
				plan.intervalFrom(), plan.target().interval(), ctx.inTrial(), token, expires);
	}

	// ── changes ─────────────────────────────────────────────────────────

	/** POST /billing/upgrade — add modules and/or seats. */
	public ChangeResponse upgrade(UserPrincipal principal, String key, ChangeRequest r) {
		return change(principal, key, Endpoint.UPGRADE, r.modules(), r.seats(), r.interval(), r.timing(), r.previewToken());
	}

	/** POST /billing/downgrade — remove modules and/or seats (default: at the next renewal). */
	public ChangeResponse downgrade(UserPrincipal principal, String key, ChangeRequest r) {
		return change(principal, key, Endpoint.DOWNGRADE, r.modules(), r.seats(), r.interval(), r.timing(), r.previewToken());
	}

	/** POST /billing/team-members — change the number of paid seats only. */
	public ChangeResponse seats(UserPrincipal principal, String key, SeatsRequest r) {
		return change(principal, key, Endpoint.SEATS, null, r.seats(), null, r.timing(), r.previewToken());
	}

	/** POST /billing/change-billing-cycle — monthly ↔ yearly; modules and seats are kept. */
	public ChangeResponse changeInterval(UserPrincipal principal, String key, IntervalRequest r) {
		return change(principal, key, Endpoint.INTERVAL, null, null, r.interval(), r.timing(), r.previewToken());
	}

	/** POST /billing/scheduled-change/cancel — undo a change booked for the next renewal. */
	public ChangeResponse cancelScheduledChange(UserPrincipal principal, String key) {
		UUID orgId = access.requireBillingAdmin(principal);
		Map<String, String> request = Map.of("action", "cancel-scheduled-change");
		Optional<ChangeResponse> done = idempotency.replayIfFinished(orgId, key, OperationType.CANCEL_SCHEDULED_CHANGE, request, ChangeResponse.class);
		if (done.isPresent()) {
			return done.get();
		}
		Ctx ctx = load(orgId);
		String scheduleId = ctx.stripeSub().getSchedule();
		if (scheduleId == null) {
			throw ApiException.conflict("There is no change booked for the next renewal.");
		}
		return idempotency.execute(orgId, principal.userId(), key, OperationType.CANCEL_SCHEDULED_CHANGE,
				request, ChangeResponse.class, op -> {
					gateway.releaseSchedule(scheduleId, op.stripeKey("release"));
					sync.syncById(ctx.stripeSub().getId());
					log.info("Booked change {} cancelled for organization {}", scheduleId, orgId);
					return new IdempotencyService.Result<>(
							new ChangeResponse("APPLIED", Instant.now(), null, null, queries.subscription(orgId)), scheduleId);
				});
	}

	/** POST /billing/subscription/refresh — re-reads the subscription from Stripe (recovers a lost link). */
	public SubscriptionResponse refresh(UserPrincipal principal) {
		UUID orgId = access.requireBillingAdmin(principal);
		OrganizationSubscription row = subscriptions.findByOrganizationId(orgId).orElse(null);
		if (row == null || row.getStripeCustomerId() == null) {
			return queries.subscription(orgId);
		}
		if (row.getStripeSubscriptionId() != null && !SubscriptionSyncService.ENDED.contains(row.getSubscriptionStatus())) {
			sync.syncById(row.getStripeSubscriptionId());
		} else {
			// No live subscription known locally: look for one in Stripe (e.g. a missed webhook).
			gateway.listSubscriptions(row.getStripeCustomerId()).stream()
					.filter(s -> !SubscriptionSyncService.ENDED.contains(s.getStatus()))
					.min(Comparator.comparing(Subscription::getCreated))
					.ifPresentOrElse(sync::sync, () -> {
						if (row.getStripeSubscriptionId() != null) {
							sync.syncById(row.getStripeSubscriptionId());
						}
					});
		}
		// Invoices too: repairs any whose status change was missed (e.g. voided while offline).
		gateway.listInvoices(row.getStripeCustomerId(), 24).forEach(invoiceSync::upsert);
		return queries.subscription(orgId);
	}

	private ChangeResponse change(UserPrincipal principal, String key, Endpoint endpoint, Set<String> modules,
			Integer seats, BillingInterval interval, ChangeTiming requestedTiming, String previewToken) {
		UUID orgId = access.requireBillingAdmin(principal);
		NormalizedChange normalized = new NormalizedChange(endpoint.name(), normalizeModules(modules), seats,
				interval == null ? null : interval.name(), requestedTiming == null ? null : requestedTiming.name());
		// A finished operation with this key returns its original answer before any re-validation.
		Optional<ChangeResponse> done = idempotency.replayIfFinished(orgId, key, endpoint.operation, normalized, ChangeResponse.class);
		if (done.isPresent()) {
			return done.get();
		}
		Ctx ctx = load(orgId);
		Plan plan = plan(ctx, modules, seats, interval);
		requireKindFor(endpoint, plan, ctx.booked() != null);
		ChangeTiming timing = timing(plan, ctx, requestedTiming);
		boolean charges = chargesNow(plan, timing, ctx);
		if (charges && "past_due".equals(ctx.row().getSubscriptionStatus())) {
			throw ApiException.conflict("There's an unpaid invoice. Update your payment method and pay it before adding to your plan.");
		}
		requireNoBookingForImmediate(ctx, timing);
		Long prorationDate = null;
		if (charges) {
			prorationDate = tokens.require(previewToken, orgId, changeHash(plan, timing), ctx.stateHash()).prorationDate();
		}

		Long proration = prorationDate;
		return idempotency.execute(orgId, principal.userId(), key, endpoint.operation, normalized, ChangeResponse.class, op -> {
			// Re-check inside the operation: nothing may have changed since the checks above.
			Ctx fresh = load(orgId);
			if (!fresh.stateHash().equals(ctx.stateHash())) {
				throw new ApiException(HttpStatus.CONFLICT, "Your subscription changed while this request was running. Preview the change again.");
			}
			ChangeResponse response = timing == ChangeTiming.IMMEDIATE
					? applyNow(fresh, plan, charges, proration, op)
					: bookForRenewal(fresh, plan, op);
			log.info("Subscription change {} ({}, {}) for organization {}: {}", endpoint, plan.kind(), timing, orgId, response.result());
			return new IdempotencyService.Result<>(response, fresh.stripeSub().getId());
		});
	}

	// ── Stripe updates ─────────────────────────────────────────────────

	private ChangeResponse applyNow(Ctx ctx, Plan plan, boolean charges, Long prorationDate, IdempotencyService.Context op) {
		if (ctx.stripeSub().getSchedule() != null && ctx.booked() == null) {
			// A schedule whose booked change already happened stays attached until its last phase
			// ends; it has nothing left to apply, so let the subscription go before changing it.
			gateway.releaseSchedule(ctx.stripeSub().getSchedule(), op.stripeKey("release-finished-schedule"));
		}
		SubscriptionUpdateParams.Builder b = SubscriptionUpdateParams.builder().addExpand("latest_invoice");
		for (ItemChange c : plan.itemChanges()) {
			SubscriptionUpdateParams.Item.Builder item = SubscriptionUpdateParams.Item.builder();
			if (c.stripeItemId() != null) {
				item.setId(c.stripeItemId());
			}
			if (c.deleted()) {
				item.setDeleted(true);
			} else {
				item.setPrice(c.priceId()).setQuantity(c.quantity());
			}
			b.addItem(item.build());
		}
		if (ctx.inTrial()) {
			b.setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE);
		} else if (charges) {
			b.setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE)
					.setProrationDate(prorationDate)
					// If the payment fails, Stripe leaves the subscription unchanged.
					.setPaymentBehavior(SubscriptionUpdateParams.PaymentBehavior.PENDING_IF_INCOMPLETE);
		} else {
			// Immediate reduction: credit unused time to the next invoice.
			b.setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS);
		}

		Subscription updated = gateway.updateSubscription(ctx.stripeSub().getId(), b.build(), op.stripeKey("update"));
		sync.syncById(updated.getId());
		SubscriptionResponse summary = queries.subscription(ctx.orgId());

		Invoice invoice = updated.getLatestInvoiceObject();
		if (updated.getPendingUpdate() != null) {
			// Payment failed or needs the customer (e.g. 3-D Secure): nothing is applied until paid.
			// The invoice embedded in the update response can lack its payment page link; read it again.
			if (invoice != null && invoice.getHostedInvoiceUrl() == null && invoice.getId() != null) {
				try {
					invoice = gateway.retrieveInvoice(invoice.getId());
				} catch (StripeCallException ex) {
					log.warn("Couldn't re-read invoice {} for its payment link: {}", invoice.getId(), ex.getMessage());
				}
			}
			return new ChangeResponse("PAYMENT_REQUIRED", null,
					invoice == null ? null : new Money(nz(invoice.getAmountDue()), invoice.getCurrency()),
					invoice == null ? null : invoice.getHostedInvoiceUrl(), summary);
		}
		Money invoiced = charges && invoice != null ? new Money(nz(invoice.getAmountDue()), invoice.getCurrency()) : null;
		return new ChangeResponse("APPLIED", Instant.now(), invoiced, null, summary);
	}

	/** Books the change for the end of the current period with a subscription schedule. */
	private ChangeResponse bookForRenewal(Ctx ctx, Plan plan, IdempotencyService.Context op) {
		Subscription sub = ctx.stripeSub();
		if (Boolean.TRUE.equals(sub.getCancelAtPeriodEnd())) {
			throw ApiException.conflict("Your subscription is set to cancel at the end of this period. Reactivate it first.");
		}
		SubscriptionSchedule schedule = sub.getSchedule() == null
				? gateway.createScheduleFromSubscription(sub.getId(), op.stripeKey("schedule-create"))
				: gateway.retrieveSchedule(sub.getSchedule());
		SubscriptionSchedule.CurrentPhase phase = schedule.getCurrentPhase();
		if (phase == null || phase.getEndDate() == null) {
			throw ApiException.conflict("The current billing period couldn't be read from Stripe. Try again shortly.");
		}

		// Phase 1: exactly what the customer has now, until the period ends.
		SubscriptionScheduleUpdateParams.Phase.Builder now = SubscriptionScheduleUpdateParams.Phase.builder()
				.setStartDate(phase.getStartDate())
				.setEndDate(phase.getEndDate())
				.setProrationBehavior(SubscriptionScheduleUpdateParams.Phase.ProrationBehavior.NONE);
		for (com.stripe.model.SubscriptionItem si : sub.getItems().getData()) {
			now.addItem(SubscriptionScheduleUpdateParams.Phase.Item.builder()
					.setPrice(si.getPrice().getId())
					.setQuantity(si.getQuantity())
					.build());
		}
		// Phase 2: the new plan from the renewal on; afterwards the schedule releases the subscription.
		SubscriptionScheduleUpdateParams.Phase.Builder next = SubscriptionScheduleUpdateParams.Phase.builder()
				.setProrationBehavior(SubscriptionScheduleUpdateParams.Phase.ProrationBehavior.NONE)
				.setDuration(SubscriptionScheduleUpdateParams.Phase.Duration.builder()
						.setInterval(plan.target().interval() == BillingInterval.YEARLY
								? SubscriptionScheduleUpdateParams.Phase.Duration.Interval.YEAR
								: SubscriptionScheduleUpdateParams.Phase.Duration.Interval.MONTH)
						.setIntervalCount(1L)
						.build());
		for (Line l : plan.targetLines()) {
			next.addItem(SubscriptionScheduleUpdateParams.Phase.Item.builder()
					.setPrice(l.priceId())
					.setQuantity(l.quantity())
					.build());
		}
		SubscriptionScheduleUpdateParams params = SubscriptionScheduleUpdateParams.builder()
				.addPhase(now.build())
				.addPhase(next.build())
				.setEndBehavior(SubscriptionScheduleUpdateParams.EndBehavior.RELEASE)
				.setMetadata(Map.of(StripeGateway.ORG_METADATA, ctx.orgId().toString()))
				.build();
		gateway.updateSchedule(schedule.getId(), params, op.stripeKey("schedule-update"));
		sync.syncById(sub.getId());
		return new ChangeResponse("SCHEDULED", Instant.ofEpochSecond(phase.getEndDate()), null, null, queries.subscription(ctx.orgId()));
	}

	// ── planning helpers ───────────────────────────────────────────────

	/** Reads the live subscription from Stripe (and syncs it), so plans always start from Stripe's truth. */
	Ctx load(UUID orgId) {
		OrganizationSubscription row = subscriptions.findByOrganizationId(orgId).orElse(null);
		if (row == null || row.getStripeSubscriptionId() == null || SubscriptionSyncService.ENDED.contains(row.getSubscriptionStatus())) {
			throw ApiException.conflict("This practice has no subscription yet. Start one first.");
		}
		Subscription s = gateway.retrieveSubscription(row.getStripeSubscriptionId());
		sync.sync(s);
		row = subscriptions.findByOrganizationId(orgId).orElseThrow();
		String status = s.getStatus();
		if (!CHANGEABLE.contains(status)) {
			throw ApiException.conflict("incomplete".equals(status)
					? "The first payment for this subscription isn't finished yet."
					: "This subscription can't be changed in its current state (" + status + ").");
		}
		if (Boolean.TRUE.equals(s.getCancelAtPeriodEnd())) {
			throw ApiException.conflict("Your subscription is set to cancel at the end of this period. Reactivate it to make changes.");
		}
		List<Current> current = new ArrayList<>();
		BillingInterval interval = row.getBillingInterval();
		List<String> fingerprint = new ArrayList<>();
		for (com.stripe.model.SubscriptionItem si : s.getItems().getData()) {
			fingerprint.add(si.getId() + ":" + si.getPrice().getId() + ":" + si.getQuantity());
			Optional<PricingCatalog.PriceInfo> info = catalog.byPriceId(si.getPrice().getId());
			if (info.isEmpty()) {
				throw ApiException.conflict("Your subscription has a price this server doesn't recognise. Contact support.");
			}
			current.add(new Current(info.get().productKey(), si.getId(), si.getPrice().getId(),
					si.getQuantity() == null ? 1 : si.getQuantity().intValue()));
		}
		List<Current> booked = null;
		BillingInterval bookedInterval = null;
		if (s.getSchedule() != null) {
			SubscriptionSchedule schedule = gateway.retrieveSchedule(s.getSchedule());
			booked = bookedPhase(schedule, catalog);
			if (booked != null) {
				bookedInterval = booked.stream().map(c -> catalog.byPriceId(c.priceId()).orElseThrow().interval())
						.findFirst().orElse(interval);
				booked.forEach(c -> fingerprint.add("booked:" + c.priceId() + ":" + c.quantity()));
			}
		}
		fingerprint.sort(null);
		String stateHash = PreviewTokens.sha256(String.join(",", fingerprint) + "|" + status + "|" + s.getSchedule()
				+ "|" + s.getCancelAtPeriodEnd());
		return new Ctx(orgId, row, s, current, interval, "trialing".equals(status), stateHash, booked, bookedInterval);
	}

	/**
	 * The plan a subscription schedule has booked for the next renewal: the phase that starts when
	 * the current phase ends. Null when there is none (e.g. the schedule is already in its last phase).
	 */
	static List<Current> bookedPhase(SubscriptionSchedule schedule, PricingCatalog catalog) {
		SubscriptionSchedule.CurrentPhase now = schedule.getCurrentPhase();
		if (now == null || now.getEndDate() == null || schedule.getPhases() == null) {
			return null;
		}
		for (SubscriptionSchedule.Phase phase : schedule.getPhases()) {
			if (!now.getEndDate().equals(phase.getStartDate())) {
				continue;
			}
			List<Current> items = new ArrayList<>();
			for (SubscriptionSchedule.Phase.Item item : phase.getItems()) {
				PricingCatalog.PriceInfo info = catalog.byPriceId(item.getPrice()).orElseThrow(() -> ApiException.conflict(
						"The change booked for your next renewal has a price this server doesn't recognise. Contact support."));
				items.add(new Current(info.productKey(), null, item.getPrice(),
						item.getQuantity() == null ? 1 : item.getQuantity().intValue()));
			}
			return items;
		}
		return null;
	}

	private static void requireNoBookingForImmediate(Ctx ctx, ChangeTiming timing) {
		// Only a change still waiting for the renewal blocks; a schedule left over from one that
		// already happened doesn't (it is released before the change, see applyNow).
		if (timing == ChangeTiming.IMMEDIATE && !ctx.inTrial() && ctx.booked() != null) {
			throw ApiException.conflict("A change is already booked for your next renewal"
					+ " (" + ctx.row().getScheduledChangeAt() + "). Cancel it first, then make this change.");
		}
	}

	private static List<String> normalizeModules(Set<String> modules) {
		if (modules == null) {
			return null;
		}
		TreeSet<String> keys = new TreeSet<>();
		for (String raw : modules) {
			keys.add(BillingModule.fromKey(raw).orElseThrow(() -> ApiException.badRequest("Unknown module: " + raw)).key());
		}
		return List.copyOf(keys);
	}

	private Plan plan(Ctx ctx, Set<String> modules, Integer seats, BillingInterval interval) {
		SortedSet<String> keys = new TreeSet<>();
		if (modules == null) {
			ctx.base().stream().map(Current::key).filter(k -> !SubscriptionItem.SEAT_KEY.equals(k)).forEach(keys::add);
		} else {
			for (String raw : modules) {
				keys.add(BillingModule.fromKey(raw).orElseThrow(() -> ApiException.badRequest("Unknown module: " + raw)).key());
			}
		}
		if (keys.isEmpty()) {
			throw ApiException.badRequest("Keep at least one module. To stop paying entirely, cancel the subscription.");
		}
		int currentSeats = ctx.base().stream().filter(c -> SubscriptionItem.SEAT_KEY.equals(c.key()))
				.mapToInt(Current::quantity).sum();
		int targetSeats = seats == null ? currentSeats : seats;
		int members = access.billableMembers(ctx.orgId());
		if (targetSeats < members) {
			throw ApiException.badRequest("Your practice has " + members + " team member" + (members == 1 ? "" : "s")
					+ " besides the owner. Remove members first, or keep at least " + members + " seat" + (members == 1 ? "" : "s") + ".");
		}
		BillingInterval targetInterval = interval == null ? ctx.baseInterval() : interval;
		// With a change already booked for renewal, the new change goes on top of it (it replaces the
		// booked phase as a whole, so starting from the current plan would silently undo the booking).
		return ChangePlanner.plan(ctx.base(), ctx.baseInterval(), new Target(keys, targetSeats, targetInterval), catalog);
	}

	private static void requireSingleKind(Plan plan, boolean booked) {
		if (plan.kind() == Kind.NO_CHANGE) {
			throw ApiException.badRequest(booked ? "That's already the plan booked for your next renewal — nothing to change."
					: "That's already your plan — nothing to change.");
		}
		if (plan.kind() == Kind.MIXED) {
			throw ApiException.badRequest("Add and remove in two separate changes: first add "
					+ describeAdds(plan) + ", then remove " + describeCuts(plan) + ".");
		}
		if (plan.kind() == Kind.INTERVAL && (!plan.addedModules().isEmpty() || !plan.removedModules().isEmpty()
				|| plan.seatsFrom() != plan.seatsTo())) {
			throw ApiException.badRequest("Switch between monthly and yearly on its own; change modules or seats separately.");
		}
	}

	private static void requireKindFor(Endpoint endpoint, Plan plan, boolean booked) {
		requireSingleKind(plan, booked);
		boolean ok = switch (endpoint) {
			case UPGRADE -> plan.kind() == Kind.UPGRADE;
			case DOWNGRADE -> plan.kind() == Kind.DOWNGRADE;
			case SEATS -> (plan.kind() == Kind.UPGRADE || plan.kind() == Kind.DOWNGRADE)
					&& plan.addedModules().isEmpty() && plan.removedModules().isEmpty();
			case INTERVAL -> plan.kind() == Kind.INTERVAL;
		};
		if (!ok) {
			String hint = switch (plan.kind()) {
				case UPGRADE -> "use /billing/upgrade";
				case DOWNGRADE -> "use /billing/downgrade";
				case INTERVAL -> "use /billing/change-billing-cycle";
				default -> "check the request";
			};
			String kind = plan.kind().name().toLowerCase();
			throw ApiException.badRequest("This request is " + ("aeiou".indexOf(kind.charAt(0)) >= 0 ? "an " : "a ") + kind + " — " + hint + ".");
		}
	}

	/** Defaults: additions now; reductions and yearly→monthly at renewal; everything now during the trial. */
	private static ChangeTiming timing(Plan plan, Ctx ctx, ChangeTiming requested) {
		if (ctx.inTrial()) {
			return ChangeTiming.IMMEDIATE;
		}
		if (requested != null) {
			return requested;
		}
		return switch (plan.kind()) {
			case UPGRADE -> ChangeTiming.IMMEDIATE;
			case INTERVAL -> plan.target().interval() == BillingInterval.YEARLY ? ChangeTiming.IMMEDIATE : ChangeTiming.PERIOD_END;
			default -> ChangeTiming.PERIOD_END;
		};
	}

	private static boolean chargesNow(Plan plan, ChangeTiming timing, Ctx ctx) {
		return !ctx.inTrial() && timing == ChangeTiming.IMMEDIATE && (plan.kind() == Kind.UPGRADE || plan.kind() == Kind.INTERVAL);
	}

	private static String changeHash(Plan plan, ChangeTiming timing) {
		return PreviewTokens.sha256(plan.kind() + "|" + timing + "|" + String.join(",", plan.target().moduleKeys())
				+ "|" + plan.target().seats() + "|" + plan.target().interval());
	}

	private InvoiceCreatePreviewParams previewParams(Ctx ctx, Plan plan, boolean charges, long prorationDate) {
		InvoiceCreatePreviewParams.SubscriptionDetails.Builder details = InvoiceCreatePreviewParams.SubscriptionDetails.builder()
				.setProrationDate(prorationDate)
				.setProrationBehavior(charges
						? InvoiceCreatePreviewParams.SubscriptionDetails.ProrationBehavior.ALWAYS_INVOICE
						: InvoiceCreatePreviewParams.SubscriptionDetails.ProrationBehavior.CREATE_PRORATIONS);
		for (ItemChange c : plan.itemChanges()) {
			InvoiceCreatePreviewParams.SubscriptionDetails.Item.Builder item = InvoiceCreatePreviewParams.SubscriptionDetails.Item.builder();
			if (c.stripeItemId() != null) {
				item.setId(c.stripeItemId());
			}
			if (c.deleted()) {
				item.setDeleted(true);
			} else {
				item.setPrice(c.priceId()).setQuantity(c.quantity());
			}
			details.addItem(item.build());
		}
		return InvoiceCreatePreviewParams.builder()
				.setCustomer(ctx.stripeSub().getCustomer())
				.setSubscription(ctx.stripeSub().getId())
				.setSubscriptionDetails(details.build())
				.build();
	}

	private static List<PreviewLine> invoiceLines(Invoice inv, Set<String> pending) {
		if (inv.getLines() == null || inv.getLines().getData() == null) {
			return List.of();
		}
		return inv.getLines().getData().stream()
				.map(l -> new PreviewLine(isChangeLine(l, pending) ? l.getDescription()
						: l.getDescription() + " (earlier credit, applied to the next regular invoice)",
						new Money(nz(l.getAmount()), inv.getCurrency())))
				.toList();
	}

	/**
	 * A line produced by this change — not an item already pending from an earlier change. Stripe's
	 * preview shows those pending items too, but the immediate change invoice doesn't charge them
	 * (they stay for the next regular invoice), so they're excluded from "due now".
	 */
	private static boolean isChangeLine(com.stripe.model.InvoiceLineItem l, Set<String> pending) {
		if (l.getParent() == null) {
			return true;
		}
		if ("invoice_item_details".equals(l.getParent().getType())) {
			return false;
		}
		var details = l.getParent().getSubscriptionItemDetails();
		return details == null || details.getInvoiceItem() == null || !pending.contains(details.getInvoiceItem());
	}

	private static List<PreviewLine> targetLines(Plan plan) {
		return plan.targetLines().stream()
				.map(l -> new PreviewLine(label(l.key()) + (l.quantity() > 1 ? " × " + l.quantity() : "") + " (" + plan.target().interval().name().toLowerCase() + ")",
						new Money(l.total(), l.currency())))
				.toList();
	}

	private static String label(String key) {
		return SubscriptionItem.SEAT_KEY.equals(key) ? "Team members"
				: BillingModule.fromKey(key).map(BillingModule::label).orElse(key);
	}

	private static String describeAdds(Plan plan) {
		List<String> parts = plan.addedModules().stream().map(SubscriptionChangeService::label).collect(Collectors.toCollection(ArrayList::new));
		if (plan.seatsTo() > plan.seatsFrom()) {
			parts.add("seats");
		}
		return String.join(", ", parts);
	}

	private static String describeCuts(Plan plan) {
		List<String> parts = plan.removedModules().stream().map(SubscriptionChangeService::label).collect(Collectors.toCollection(ArrayList::new));
		if (plan.seatsTo() < plan.seatsFrom()) {
			parts.add("seats");
		}
		return String.join(", ", parts);
	}

	private static long nz(Long v) {
		return v == null ? 0L : v;
	}
}
