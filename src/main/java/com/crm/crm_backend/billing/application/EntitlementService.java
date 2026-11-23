package com.crm.crm_backend.billing.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.crm.crm_backend.billing.domain.BillingModule;
import com.crm.crm_backend.billing.domain.OrganizationSubscription;
import com.crm.crm_backend.billing.domain.SubscriptionItem;
import com.crm.crm_backend.billing.domain.SubscriptionItemStatus;
import com.crm.crm_backend.billing.infrastructure.OrganizationSubscriptionRepository;
import com.crm.crm_backend.billing.infrastructure.SubscriptionItemRepository;
import com.crm.crm_backend.organization.domain.Organization;
import com.crm.crm_backend.organization.infrastructure.OrganizationRepository;
import com.crm.crm_backend.shared.exception.ApiException;

/**
 * The single answer to "what may this practice use right now?". Trial access and paid
 * entitlements are kept apart:
 * <ul>
 * <li><b>Trial</b> (from signup until it ends or paid billing starts — then it's over for good,
 * even if the subscription is cancelled later): all four modules,
 * owner + {@code billing.trial-max-members} team members.</li>
 * <li><b>Paid</b>: the modules and seats on the Stripe subscription. A module booked for removal
 * stays available until the renewal; a scheduled cancellation keeps access until period end.</li>
 * <li><b>Payment overdue</b>: paid access continues during the grace period, then stops.</li>
 * </ul>
 * Restricting access never deletes data. Billing, workspace, team and sign-in are never blocked.
 */
@Service
public class EntitlementService {

	/** How long a computed answer is reused (billing changes evict it immediately). */
	private static final Duration CACHE_TTL = Duration.ofSeconds(20);
	private static final Set<String> PAID_LIVE = Set.of("active", "past_due");

	public enum State {
		/** Free trial: all modules. */
		TRIAL,
		/** Paid subscription in good standing. */
		ACTIVE,
		/** Payment failed; still usable until the grace period ends. */
		PAST_DUE,
		/** Grace period over or subscription unpaid: modules blocked until payment. */
		RESTRICTED,
		/** No trial left and no paid subscription (never subscribed, cancelled, or first payment pending). */
		INACTIVE
	}

	public record Entitlements(
			UUID organizationId,
			State state,
			boolean trialActive,
			Instant trialEndsAt,
			boolean paidActive,
			Set<String> modules,
			/** Team members allowed besides the owner. */
			int maxMembers,
			/** Team members now (owner excluded). */
			int members,
			boolean paymentOverdue,
			Instant gracePeriodEndsAt,
			boolean cancellationScheduled,
			/** When access ends (trial end, cancellation date, grace end), if known. */
			Instant accessEndsAt,
			String subscriptionStatus,
			/** The practice has paid before (so a lapse means "subscription ended", not "trial ended"). */
			boolean everPaid,
			Instant computedAt
	) {
		public boolean allows(BillingModule module) {
			return modules.contains(module.key());
		}

		public int seatsLeft() {
			return Math.max(0, maxMembers - members);
		}
	}

	private record Cached(Entitlements value, Instant at) {
	}

	private final OrganizationRepository organizations;
	private final OrganizationSubscriptionRepository subscriptions;
	private final SubscriptionItemRepository items;
	private final BillingAccess access;
	private final StripeProperties props;
	private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

	public EntitlementService(OrganizationRepository organizations, OrganizationSubscriptionRepository subscriptions,
			SubscriptionItemRepository items, BillingAccess access, StripeProperties props) {
		this.organizations = organizations;
		this.subscriptions = subscriptions;
		this.items = items;
		this.access = access;
		this.props = props;
	}

	public Entitlements forOrganization(UUID orgId) {
		Cached c = cache.get(orgId);
		Instant now = Instant.now();
		if (c != null && c.at().plus(CACHE_TTL).isAfter(now)) {
			return c.value();
		}
		Entitlements e = compute(orgId, now);
		cache.put(orgId, new Cached(e, now));
		return e;
	}

	/** Called whenever billing state changes (sync, payment failure, cancellation). */
	public void evict(UUID orgId) {
		if (orgId != null) {
			cache.remove(orgId);
		}
	}

	/** 402 unless the practice may use this module right now. */
	public void requireModule(UUID orgId, BillingModule module) {
		Entitlements e = forOrganization(orgId);
		if (e.allows(module)) {
			return;
		}
		String why = switch (e.state()) {
			case RESTRICTED -> "Your subscription payment is overdue, so " + module.label()
					+ " is paused. Update your payment method in Billing to restore access — your data is safe.";
			case INACTIVE -> e.everPaid()
					? "Your subscription has ended. Start a new subscription in Billing to use " + module.label() + " again — your data is safe."
					: "Your free trial has ended. Choose a plan in Billing to keep using " + module.label() + ".";
			default -> module.label() + " isn't part of your plan. Add it in Billing to use it.";
		};
		throw new ApiException(HttpStatus.PAYMENT_REQUIRED, why);
	}

	/**
	 * Call before adding a team member (owner excluded): 402 when the trial's member cap or the
	 * paid seat count is reached. Use from any future invite / add-member flow.
	 */
	public void requireSeatForNewMember(UUID orgId) {
		Entitlements e = forOrganization(orgId);
		if (e.seatsLeft() > 0) {
			return;
		}
		throw new ApiException(HttpStatus.PAYMENT_REQUIRED, e.trialActive()
				? "The free trial includes up to " + e.maxMembers() + " team members besides the owner."
				: "All " + e.maxMembers() + " paid seats are in use. Add seats in Billing to invite more team members.");
	}

	Entitlements compute(UUID orgId, Instant now) {
		Organization org = organizations.findById(orgId).orElse(null);
		if (org == null) {
			return new Entitlements(orgId, State.INACTIVE, false, null, false, Set.of(), 0, 0, false, null, false, null,
					OrganizationSubscription.STATUS_NONE, false, now);
		}
		OrganizationSubscription row = subscriptions.findByOrganizationId(orgId).orElse(null);
		int members = access.billableMembers(orgId);
		String status = row == null ? OrganizationSubscription.STATUS_NONE : row.getSubscriptionStatus();
		boolean hasLiveSubscription = row != null && row.getStripeSubscriptionId() != null
				&& !SubscriptionSyncService.ENDED.contains(status);

		// ── trial: from signup; ends for good once paid billing has started (even if cancelled later) ──
		Instant trialEnd = org.getTrialEndsAt();
		if (row != null && "trialing".equals(status) && row.getTrialEnd() != null) {
			trialEnd = row.getTrialEnd();
		}
		boolean billingStarted = (row != null && row.getPaidBillingStartedAt() != null)
				|| (hasLiveSubscription && !"trialing".equals(status) && !"incomplete".equals(status));
		boolean trialActive = trialEnd != null && trialEnd.isAfter(now) && !billingStarted;

		// ── paid entitlement from the subscription ──
		Set<String> paidModules = new TreeSet<>();
		int paidSeats = 0;
		if (row != null && row.getStripeSubscriptionId() != null) {
			for (SubscriptionItem i : items.findBySubscriptionIdAndStatusIn(row.getId(),
					List.of(SubscriptionItemStatus.ACTIVE, SubscriptionItemStatus.SCHEDULED_REMOVAL))) {
				if (i.isSeat()) {
					paidSeats = i.getQuantity();
				} else {
					paidModules.add(i.getModuleKey());
				}
			}
		}
		Instant graceEnd = null;
		boolean overdue = "past_due".equals(status) || "unpaid".equals(status);
		if ("past_due".equals(status)) {
			graceEnd = row.getGracePeriodEndsAt();
			if (graceEnd == null) {
				// Missed webhook: count the grace period from the start of the unpaid period.
				Instant from = row.getPaymentFailedAt() != null ? row.getPaymentFailedAt()
						: row.getCurrentPeriodStart() != null ? row.getCurrentPeriodStart() : now;
				graceEnd = from.plus(Duration.ofDays(props.gracePeriodDays()));
			}
		}
		boolean paidActive = PAID_LIVE.contains(status) && !("past_due".equals(status) && !graceEnd.isAfter(now));
		boolean cancelScheduled = row != null && (row.isCancelAtPeriodEnd() || row.getCancelAt() != null)
				&& hasLiveSubscription;

		State state;
		Set<String> modules;
		int maxMembers;
		Instant accessEnds;
		if (trialActive) {
			state = State.TRIAL;
			modules = allModules();
			maxMembers = props.trialMaxMembers();
			accessEnds = trialEnd;
		} else if (paidActive) {
			state = "past_due".equals(status) ? State.PAST_DUE : State.ACTIVE;
			modules = paidModules;
			maxMembers = paidSeats;
			accessEnds = "past_due".equals(status) ? graceEnd
					: cancelScheduled ? (row.getCancelAt() != null ? row.getCancelAt() : row.getCurrentPeriodEnd()) : null;
		} else if (overdue) {
			state = State.RESTRICTED;
			modules = Set.of();
			maxMembers = paidSeats;
			accessEnds = graceEnd;
		} else {
			state = State.INACTIVE;
			modules = Set.of();
			maxMembers = 0;
			accessEnds = null;
		}
		return new Entitlements(orgId, state, trialActive, trialEnd, paidActive, Set.copyOf(modules), maxMembers, members,
				overdue, graceEnd, cancelScheduled, accessEnds, status,
				row != null && row.getPaidBillingStartedAt() != null, now);
	}

	private static Set<String> allModules() {
		Set<String> all = new TreeSet<>();
		Arrays.stream(BillingModule.values()).forEach(m -> all.add(m.key()));
		return all;
	}
}
