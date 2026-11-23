package com.crm.crm_backend.billing.application;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.crm.crm_backend.billing.domain.BillingInterval;
import com.crm.crm_backend.billing.domain.OrganizationSubscription;
import com.crm.crm_backend.billing.domain.SubscriptionItem;
import com.crm.crm_backend.billing.domain.SubscriptionItemStatus;
import com.crm.crm_backend.billing.infrastructure.OrganizationSubscriptionRepository;
import com.crm.crm_backend.billing.infrastructure.SubscriptionItemRepository;
import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionSchedule;

/**
 * Copies a subscription's current state from Stripe into the local tables. Callers always pass a
 * subscription freshly retrieved from Stripe (never webhook payload data), so the order in which
 * events arrive doesn't matter: the newest state always wins.
 */
@Service
public class SubscriptionSyncService {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionSyncService.class);

	/** Stripe statuses after which a subscription no longer counts as the organization's subscription. */
	public static final Set<String> ENDED = Set.of("canceled", "incomplete_expired");

	public enum Outcome {
		APPLIED,
		/** A second live subscription for an organization that already has one: not applied. */
		DUPLICATE,
		/** No organization could be matched. */
		UNMATCHED
	}

	private final OrganizationSubscriptionRepository subscriptions;
	private final SubscriptionItemRepository items;
	private final PricingCatalog catalog;
	private final StripeGateway gateway;
	private final EntitlementService entitlements;
	private final TransactionTemplate tx;

	public SubscriptionSyncService(OrganizationSubscriptionRepository subscriptions, SubscriptionItemRepository items,
			PricingCatalog catalog, StripeGateway gateway, EntitlementService entitlements, PlatformTransactionManager txManager) {
		this.subscriptions = subscriptions;
		this.items = items;
		this.catalog = catalog;
		this.gateway = gateway;
		this.entitlements = entitlements;
		this.tx = new TransactionTemplate(txManager);
	}

	/** Re-reads the subscription from Stripe and syncs it. */
	public Outcome syncById(String subscriptionId) {
		return sync(gateway.retrieveSubscription(subscriptionId));
	}

	public Outcome sync(Subscription s) {
		UUID orgId = resolveOrganization(s).orElse(null);
		if (orgId == null) {
			log.warn("Stripe subscription {} has no matching organization; ignored", s.getId());
			return Outcome.UNMATCHED;
		}
		ensureRow(orgId, s.getCustomer());
		// Read the attached schedule (a change booked for the next renewal) before opening a transaction.
		SubscriptionSchedule schedule = null;
		if (s.getSchedule() != null && !ENDED.contains(s.getStatus())) {
			schedule = gateway.retrieveSchedule(s.getSchedule());
		}
		SubscriptionSchedule sched = schedule;
		try {
			return tx.execute(status -> apply(orgId, s, sched));
		} finally {
			entitlements.evict(orgId);
		}
	}

	public Optional<UUID> resolveOrganization(Subscription s) {
		if (s.getMetadata() != null && s.getMetadata().get(StripeGateway.ORG_METADATA) != null) {
			try {
				return Optional.of(UUID.fromString(s.getMetadata().get(StripeGateway.ORG_METADATA)));
			} catch (IllegalArgumentException ignored) {
				// fall through to the local lookups
			}
		}
		return subscriptions.findByStripeSubscriptionId(s.getId())
				.or(() -> s.getCustomer() == null ? Optional.empty() : subscriptions.findByStripeCustomerId(s.getCustomer()))
				.map(OrganizationSubscription::getOrganizationId);
	}

	/** Creates the organization's billing row if it doesn't exist yet (safe under concurrency). */
	public void ensureRow(UUID orgId, String customerId) {
		if (subscriptions.findByOrganizationId(orgId).isPresent()) {
			return;
		}
		try {
			tx.executeWithoutResult(status -> {
				OrganizationSubscription row = new OrganizationSubscription();
				row.setOrganizationId(orgId);
				row.setStripeCustomerId(customerId);
				subscriptions.saveAndFlush(row);
			});
		} catch (DataIntegrityViolationException alreadyCreated) {
			// another request created it first
		}
	}

	private Outcome apply(UUID orgId, Subscription s, SubscriptionSchedule schedule) {
		OrganizationSubscription row = subscriptions.lockByOrganizationId(orgId).orElseThrow();
		String status = s.getStatus() == null ? "incomplete" : s.getStatus().toLowerCase();

		if (row.getStripeSubscriptionId() != null && !row.getStripeSubscriptionId().equals(s.getId())) {
			boolean currentLive = !ENDED.contains(row.getSubscriptionStatus());
			if (currentLive && !ENDED.contains(status)) {
				log.error("Organization {} already has subscription {}; second live subscription {} not applied",
						orgId, row.getStripeSubscriptionId(), s.getId());
				return Outcome.DUPLICATE;
			}
			if (currentLive || ENDED.contains(status)) {
				// An old, ended subscription reporting in late: keep the current one.
				return Outcome.APPLIED;
			}
			// The previous subscription ended and this is the new one.
			for (SubscriptionItem old : items.findBySubscriptionIdAndStatusIn(row.getId(), List.of(
					SubscriptionItemStatus.ACTIVE, SubscriptionItemStatus.SCHEDULED_ADD, SubscriptionItemStatus.SCHEDULED_REMOVAL))) {
				old.setStatus(SubscriptionItemStatus.REMOVED);
			}
			items.flush();
			row.setStripeScheduleId(null);
			row.setScheduledInterval(null);
			row.setScheduledChangeAt(null);
		}

		row.setStripeSubscriptionId(s.getId());
		if (s.getCustomer() != null) {
			row.setStripeCustomerId(s.getCustomer());
		}
		row.setSubscriptionStatus(status);
		row.setTrialStart(instant(s.getTrialStart()));
		row.setTrialEnd(instant(s.getTrialEnd()));
		row.setCancelAtPeriodEnd(Boolean.TRUE.equals(s.getCancelAtPeriodEnd()));
		row.setCancelAt(instant(s.getCancelAt()));
		row.setCanceledAt(instant(s.getCanceledAt()));
		row.setEndedAt(instant(s.getEndedAt()));
		row.setStripeScheduleId(s.getSchedule());
		row.setLatestInvoiceId(s.getLatestInvoice());

		List<com.stripe.model.SubscriptionItem> stripeItems = s.getItems() == null ? List.of() : s.getItems().getData();
		Instant periodStart = null;
		Instant periodEnd = null;
		BillingInterval interval = null;
		for (com.stripe.model.SubscriptionItem si : stripeItems) {
			Instant start = instant(si.getCurrentPeriodStart());
			Instant end = instant(si.getCurrentPeriodEnd());
			if (start != null && (periodStart == null || start.isBefore(periodStart))) {
				periodStart = start;
			}
			if (end != null && (periodEnd == null || end.isAfter(periodEnd))) {
				periodEnd = end;
			}
			if (interval == null && si.getPrice() != null && si.getPrice().getRecurring() != null) {
				interval = "year".equals(si.getPrice().getRecurring().getInterval()) ? BillingInterval.YEARLY : BillingInterval.MONTHLY;
			}
		}
		row.setCurrentPeriodStart(periodStart);
		row.setCurrentPeriodEnd(periodEnd);
		if (interval != null) {
			row.setBillingInterval(interval);
		}
		if (row.getPaidBillingStartedAt() == null && Set.of("active", "past_due", "unpaid").contains(status)) {
			// Paid billing has started: the free trial is over for good, even if cancelled later.
			row.setPaidBillingStartedAt(Instant.now());
		}
		if ("active".equals(status) || "trialing".equals(status)) {
			// A healthy subscription has no failed payment to recover from.
			row.setPaymentFailedAt(null);
			row.setGracePeriodEndsAt(null);
		}
		row.setPendingCheckoutSessionId(null);
		row.setPendingCheckoutExpiresAt(null);
		row.setStripeSyncedAt(Instant.now());
		subscriptions.save(row);

		syncItems(row, stripeItems);
		projectSchedule(row, schedule);
		subscriptions.save(row);
		return Outcome.APPLIED;
	}

	/**
	 * Mirrors a change booked for the next renewal: modules leaving become SCHEDULED_REMOVAL (still
	 * usable until then), new or changed lines become SCHEDULED_ADD rows.
	 */
	private void projectSchedule(OrganizationSubscription row, SubscriptionSchedule schedule) {
		row.setScheduledInterval(null);
		row.setScheduledChangeAt(null);
		if (schedule == null || schedule.getPhases() == null || !"active".equals(schedule.getStatus())) {
			return;
		}
		long now = Instant.now().getEpochSecond();
		SubscriptionSchedule.Phase next = schedule.getPhases().stream()
				.filter(ph -> ph.getStartDate() != null && ph.getStartDate() > now)
				.min((a, b) -> Long.compare(a.getStartDate(), b.getStartDate()))
				.orElse(null);
		if (next == null || next.getItems() == null) {
			return;
		}
		List<SubscriptionItem> active = items.findBySubscriptionIdAndStatusIn(row.getId(), List.of(SubscriptionItemStatus.ACTIVE));
		java.util.Map<String, SubscriptionItem> currentByKey = new java.util.HashMap<>();
		active.forEach(i -> currentByKey.put(i.getModuleKey(), i));
		Set<String> nextKeys = new HashSet<>();
		BillingInterval nextInterval = null;
		for (SubscriptionSchedule.Phase.Item pi : next.getItems()) {
			PricingCatalog.PriceInfo info = pi.getPrice() == null ? null : safePrice(pi.getPrice());
			if (info == null) {
				log.warn("Scheduled phase of {} uses unknown price {}", row.getStripeSubscriptionId(), pi.getPrice());
				continue;
			}
			nextKeys.add(info.productKey());
			nextInterval = info.interval();
			int qty = pi.getQuantity() == null ? 1 : pi.getQuantity().intValue();
			SubscriptionItem cur = currentByKey.get(info.productKey());
			if (cur != null && cur.getStripePriceId().equals(info.priceId()) && cur.getQuantity() == qty) {
				continue; // unchanged at renewal
			}
			SubscriptionItem add = new SubscriptionItem();
			add.setSubscriptionId(row.getId());
			add.setModuleKey(info.productKey());
			add.setStripePriceId(info.priceId());
			add.setQuantity(qty);
			add.setBillingInterval(info.interval());
			add.setUnitAmount(info.unitAmount());
			add.setCurrency(info.currency());
			add.setStatus(SubscriptionItemStatus.SCHEDULED_ADD);
			items.save(add);
		}
		for (SubscriptionItem cur : active) {
			if (!nextKeys.contains(cur.getModuleKey())) {
				cur.setStatus(SubscriptionItemStatus.SCHEDULED_REMOVAL);
				items.save(cur);
			}
		}
		items.flush();
		row.setScheduledChangeAt(instant(next.getStartDate()));
		if (nextInterval != null && nextInterval != row.getBillingInterval()) {
			row.setScheduledInterval(nextInterval);
		}
	}

	private PricingCatalog.PriceInfo safePrice(String priceId) {
		try {
			return catalog.byPriceId(priceId).orElse(null);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/** Makes the local ACTIVE items match the subscription's items in Stripe (matched by item id). */
	private void syncItems(OrganizationSubscription row, List<com.stripe.model.SubscriptionItem> stripeItems) {
		// 0 · Forget the previous projection of a booked change; it is rebuilt from the schedule.
		for (SubscriptionItem i : items.findBySubscriptionId(row.getId())) {
			if (i.getStatus() == SubscriptionItemStatus.SCHEDULED_ADD) {
				items.delete(i);
			} else if (i.getStatus() == SubscriptionItemStatus.SCHEDULED_REMOVAL) {
				i.setStatus(SubscriptionItemStatus.ACTIVE);
				items.save(i);
			}
		}
		items.flush();

		List<SubscriptionItem> local = items.findBySubscriptionId(row.getId());
		Set<String> liveIds = new HashSet<>();
		stripeItems.forEach(si -> liveIds.add(si.getId()));

		// 1 · Retire items that are no longer on the subscription (before inserting replacements).
		for (SubscriptionItem item : local) {
			if (item.getStatus() == SubscriptionItemStatus.ACTIVE
					&& (item.getStripeSubscriptionItemId() == null || !liveIds.contains(item.getStripeSubscriptionItemId()))) {
				item.setStatus(SubscriptionItemStatus.REMOVED);
			}
		}
		items.saveAll(local);
		items.flush();

		// 2 · Upsert the live items.
		for (com.stripe.model.SubscriptionItem si : stripeItems) {
			Price price = si.getPrice();
			if (price == null) {
				continue;
			}
			Optional<PricingCatalog.PriceInfo> info;
			try {
				info = catalog.byPriceId(price.getId());
			} catch (RuntimeException ex) {
				info = Optional.empty();
			}
			String key = info.map(PricingCatalog.PriceInfo::productKey).orElse(null);
			if (key == null) {
				log.warn("Subscription {} has price {} that isn't in the configured price list; item {} not tracked",
						row.getStripeSubscriptionId(), price.getId(), si.getId());
				continue;
			}
			SubscriptionItem item = local.stream()
					.filter(l -> si.getId().equals(l.getStripeSubscriptionItemId()))
					.findFirst()
					.orElseGet(SubscriptionItem::new);
			item.setSubscriptionId(row.getId());
			item.setModuleKey(key);
			item.setStripeSubscriptionItemId(si.getId());
			item.setStripePriceId(price.getId());
			item.setQuantity(si.getQuantity() == null ? 1 : si.getQuantity().intValue());
			item.setBillingInterval("year".equals(price.getRecurring() == null ? null : price.getRecurring().getInterval())
					? BillingInterval.YEARLY : BillingInterval.MONTHLY);
			item.setUnitAmount(price.getUnitAmount() == null ? 0L : price.getUnitAmount());
			item.setCurrency(price.getCurrency());
			item.setStatus(SubscriptionItemStatus.ACTIVE);
			items.save(item);
		}
		items.flush();
	}

	static Instant instant(Long epochSeconds) {
		return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds);
	}
}
