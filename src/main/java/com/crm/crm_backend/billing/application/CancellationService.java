package com.crm.crm_backend.billing.application;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.crm.crm_backend.billing.api.dto.BillingDtos.CancelRequest;
import com.crm.crm_backend.billing.api.dto.BillingDtos.ChangeResponse;
import com.crm.crm_backend.billing.domain.OperationType;
import com.crm.crm_backend.billing.domain.OrganizationSubscription;
import com.crm.crm_backend.billing.infrastructure.OrganizationSubscriptionRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.stripe.model.Subscription;
import com.stripe.param.SubscriptionUpdateParams;

/**
 * Cancelling and reactivating the subscription.
 * <ul>
 * <li>{@code PERIOD_END} (default): access continues until the paid period (or the trial) ends;
 * nothing more is charged. Can be undone with reactivate until then.</li>
 * <li>{@code IMMEDIATE}: the subscription ends now; no further invoices and no proration credit.
 * During the free trial nothing has been charged.</li>
 * </ul>
 * The free trial belongs to the practice (from signup), so cancelling during it doesn't cut the
 * remaining trial days short. Data is never deleted on cancellation.
 */
@Service
public class CancellationService {

	private static final Logger log = LoggerFactory.getLogger(CancellationService.class);

	public enum Mode { IMMEDIATE, PERIOD_END }

	private final BillingAccess access;
	private final StripeGateway gateway;
	private final IdempotencyService idempotency;
	private final SubscriptionSyncService sync;
	private final OrganizationSubscriptionRepository subscriptions;
	private final BillingQueryService queries;

	public CancellationService(BillingAccess access, StripeGateway gateway, IdempotencyService idempotency,
			SubscriptionSyncService sync, OrganizationSubscriptionRepository subscriptions, BillingQueryService queries) {
		this.access = access;
		this.gateway = gateway;
		this.idempotency = idempotency;
		this.sync = sync;
		this.subscriptions = subscriptions;
		this.queries = queries;
	}

	/** POST /billing/cancel */
	public ChangeResponse cancel(UserPrincipal principal, String key, CancelRequest request) {
		UUID orgId = access.requireBillingAdmin(principal);
		Mode mode = request == null || request.mode() == null ? Mode.PERIOD_END : Mode.valueOf(request.mode().name());
		Map<String, String> normalized = Map.of("mode", mode.name());
		Optional<ChangeResponse> done = idempotency.replayIfFinished(orgId, key, OperationType.CANCEL, normalized, ChangeResponse.class);
		if (done.isPresent()) {
			return done.get();
		}
		Subscription s = liveSubscription(orgId);
		if (mode == Mode.PERIOD_END && Boolean.TRUE.equals(s.getCancelAtPeriodEnd())) {
			throw ApiException.conflict("Cancellation is already scheduled for the end of this period.");
		}
		return idempotency.execute(orgId, principal.userId(), key, OperationType.CANCEL, normalized, ChangeResponse.class, op -> {
			Instant effective;
			if (mode == Mode.IMMEDIATE) {
				gateway.cancelSubscriptionNow(s.getId(), op.stripeKey("cancel-now"));
				effective = Instant.now();
			} else {
				if (s.getSchedule() != null) {
					// A booked renewal change can't coexist with ending at period end.
					gateway.releaseSchedule(s.getSchedule(), op.stripeKey("release-before-cancel"));
				}
				gateway.updateSubscription(s.getId(),
						SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(true).build(), op.stripeKey("cancel-at-period-end"));
				effective = "trialing".equals(s.getStatus()) ? SubscriptionSyncService.instant(s.getTrialEnd()) : periodEnd(s);
			}
			sync.syncById(s.getId());
			log.info("Subscription {} of organization {} cancelled ({})", s.getId(), orgId, mode);
			return new IdempotencyService.Result<>(new ChangeResponse(mode == Mode.IMMEDIATE ? "CANCELED" : "CANCEL_SCHEDULED",
					effective, null, null, queries.subscription(orgId)), s.getId());
		});
	}

	/** POST /billing/reactivate — undoes a cancellation scheduled for period end. */
	public ChangeResponse reactivate(UserPrincipal principal, String key) {
		UUID orgId = access.requireBillingAdmin(principal);
		Map<String, String> normalized = Map.of("action", "reactivate");
		Optional<ChangeResponse> done = idempotency.replayIfFinished(orgId, key, OperationType.REACTIVATE, normalized, ChangeResponse.class);
		if (done.isPresent()) {
			return done.get();
		}
		Subscription s = liveSubscription(orgId);
		if (!Boolean.TRUE.equals(s.getCancelAtPeriodEnd()) && s.getCancelAt() == null) {
			throw ApiException.conflict("Your subscription isn't scheduled to cancel — nothing to reactivate.");
		}
		return idempotency.execute(orgId, principal.userId(), key, OperationType.REACTIVATE, normalized, ChangeResponse.class, op -> {
			gateway.updateSubscription(s.getId(),
					SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(false).build(), op.stripeKey("reactivate"));
			sync.syncById(s.getId());
			log.info("Subscription {} of organization {} reactivated", s.getId(), orgId);
			return new IdempotencyService.Result<>(new ChangeResponse("REACTIVATED", Instant.now(), null, null,
					queries.subscription(orgId)), s.getId());
		});
	}

	private Subscription liveSubscription(UUID orgId) {
		OrganizationSubscription row = subscriptions.findByOrganizationId(orgId).orElse(null);
		if (row == null || row.getStripeSubscriptionId() == null || SubscriptionSyncService.ENDED.contains(row.getSubscriptionStatus())) {
			throw ApiException.conflict("There's no active subscription. To use Utessa again, start a new subscription.");
		}
		Subscription s = gateway.retrieveSubscription(row.getStripeSubscriptionId());
		if (SubscriptionSyncService.ENDED.contains(s.getStatus())) {
			sync.sync(s);
			throw ApiException.conflict("This subscription has already ended. Start a new subscription instead.");
		}
		return s;
	}

	private static Instant periodEnd(Subscription s) {
		Long end = null;
		if (s.getItems() != null) {
			for (com.stripe.model.SubscriptionItem i : s.getItems().getData()) {
				if (i.getCurrentPeriodEnd() != null && (end == null || i.getCurrentPeriodEnd() > end)) {
					end = i.getCurrentPeriodEnd();
				}
			}
		}
		return SubscriptionSyncService.instant(end);
	}
}
