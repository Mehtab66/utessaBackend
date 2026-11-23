package com.crm.crm_backend.billing.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.crm.crm_backend.billing.api.dto.BillingDtos.ItemResponse;
import com.crm.crm_backend.billing.api.dto.BillingDtos.ModulePrice;
import com.crm.crm_backend.billing.api.dto.BillingDtos.Money;
import com.crm.crm_backend.billing.api.dto.BillingDtos.PricingResponse;
import com.crm.crm_backend.billing.api.dto.BillingDtos.SeatInfo;
import com.crm.crm_backend.billing.api.dto.BillingDtos.SubscriptionResponse;
import com.crm.crm_backend.billing.api.dto.BillingDtos.TrialInfo;
import com.crm.crm_backend.billing.domain.BillingInterval;
import com.crm.crm_backend.billing.domain.BillingModule;
import com.crm.crm_backend.billing.domain.OrganizationSubscription;
import com.crm.crm_backend.billing.domain.SubscriptionItem;
import com.crm.crm_backend.billing.domain.SubscriptionItemStatus;
import com.crm.crm_backend.billing.infrastructure.OrganizationSubscriptionRepository;
import com.crm.crm_backend.billing.infrastructure.SubscriptionItemRepository;
import com.crm.crm_backend.organization.domain.Organization;
import com.crm.crm_backend.shared.security.UserPrincipal;

/** Read-only billing views: the price list and the organization's subscription state. */
@Service
public class BillingQueryService {

	private final BillingAccess access;
	private final PricingCatalog catalog;
	private final StripeProperties props;
	private final OrganizationSubscriptionRepository subscriptions;
	private final SubscriptionItemRepository items;
	private final com.crm.crm_backend.billing.infrastructure.SubscriptionInvoiceRepository invoices;

	public BillingQueryService(BillingAccess access, PricingCatalog catalog, StripeProperties props,
			OrganizationSubscriptionRepository subscriptions, SubscriptionItemRepository items,
			com.crm.crm_backend.billing.infrastructure.SubscriptionInvoiceRepository invoices) {
		this.access = access;
		this.catalog = catalog;
		this.props = props;
		this.subscriptions = subscriptions;
		this.items = items;
		this.invoices = invoices;
	}

	/** GET /billing/pricing — any practice member. Amounts come from Stripe, never hard-coded. */
	public PricingResponse pricing(UserPrincipal principal) {
		access.requireMember(principal);
		List<ModulePrice> modules = Arrays.stream(BillingModule.values())
				.map(m -> new ModulePrice(m.key(), m.label(),
						money(catalog.modulePrice(m, BillingInterval.MONTHLY)),
						money(catalog.modulePrice(m, BillingInterval.YEARLY))))
				.toList();
		return new PricingResponse(
				catalog.currency(),
				props.trialDays(),
				props.trialMaxMembers(),
				true,
				props.publishableKey(),
				modules,
				money(catalog.seatPrice(BillingInterval.MONTHLY)),
				money(catalog.seatPrice(BillingInterval.YEARLY)));
	}

	/** GET /billing/invoices — owner/admin; newest first. */
	public List<com.crm.crm_backend.billing.api.dto.BillingDtos.InvoiceResponse> invoices(UserPrincipal principal, int limit) {
		UUID orgId = access.requireBillingAdmin(principal);
		return invoices.findByOrganizationIdOrderByStripeCreatedAtDesc(orgId,
						org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
				.stream()
				.map(i -> new com.crm.crm_backend.billing.api.dto.BillingDtos.InvoiceResponse(
						i.getInvoiceNumber(), i.getInvoiceStatus(), i.getBillingReason(),
						new Money(i.getAmountDue(), i.getCurrency()), new Money(i.getAmountPaid(), i.getCurrency()),
						new Money(i.getAmountRemaining(), i.getCurrency()), i.getPeriodStart(), i.getPeriodEnd(),
						i.getStripeCreatedAt(), i.getPaidAt(), i.getAttemptCount(), i.getNextPaymentAttempt(),
						i.getHostedInvoiceUrl(), i.getInvoicePdfUrl()))
				.toList();
	}

	/** GET /billing/subscription — owner/admin. */
	public SubscriptionResponse subscription(UserPrincipal principal) {
		return subscription(access.requireBillingAdmin(principal));
	}

	public SubscriptionResponse subscription(UUID orgId) {
		Organization org = access.organization(orgId);
		OrganizationSubscription row = subscriptions.findByOrganizationId(orgId).orElse(null);
		int used = access.billableMembers(orgId);

		List<SubscriptionItem> live = row == null ? List.of() : items.findBySubscriptionIdAndStatusIn(row.getId(),
				List.of(SubscriptionItemStatus.ACTIVE, SubscriptionItemStatus.SCHEDULED_ADD, SubscriptionItemStatus.SCHEDULED_REMOVAL));
		List<ItemResponse> itemResponses = live.stream()
				.sorted(Comparator.comparing(SubscriptionItem::isSeat).thenComparing(SubscriptionItem::getModuleKey))
				.map(i -> new ItemResponse(i.getModuleKey(), label(i.getModuleKey()), i.getQuantity(),
						new Money(i.getUnitAmount(), i.getCurrency()), i.getBillingInterval(), i.getStatus().name()))
				.toList();
		int purchased = live.stream()
				.filter(i -> i.isSeat() && i.getStatus() != SubscriptionItemStatus.SCHEDULED_ADD)
				.mapToInt(SubscriptionItem::getQuantity).sum();

		Instant now = Instant.now();
		Instant trialStart = org.getTrialStartsAt();
		Instant trialEnd = org.getTrialEndsAt();
		if (row != null && row.getTrialEnd() != null) {
			trialEnd = row.getTrialEnd();
		}
		boolean trialActive = trialEnd != null && trialEnd.isAfter(now)
				&& (row == null || row.getStripeSubscriptionId() == null || "trialing".equals(row.getSubscriptionStatus()));
		long daysLeft = trialActive ? Math.max(0, (long) Math.ceil(Duration.between(now, trialEnd).toHours() / 24.0)) : 0;
		TrialInfo trial = new TrialInfo(trialActive, trialStart, trialEnd, daysLeft, props.trialMaxMembers());

		if (row == null) {
			return new SubscriptionResponse(OrganizationSubscription.STATUS_NONE, null, trial, null, null, false, null, null,
					List.of(), new SeatInfo(0, used), false, null, null, null, null, null, null);
		}
		return new SubscriptionResponse(
				row.getSubscriptionStatus(),
				row.getBillingInterval(),
				trial,
				row.getCurrentPeriodStart(),
				row.getCurrentPeriodEnd(),
				row.isCancelAtPeriodEnd(),
				row.getCancelAt(),
				row.getCanceledAt(),
				itemResponses,
				new SeatInfo(purchased, used),
				row.getStripeCustomerId() != null,
				row.getPendingCheckoutSessionId(),
				row.getPendingCheckoutExpiresAt(),
				row.getPaymentFailedAt(),
				row.getGracePeriodEndsAt(),
				row.getScheduledInterval(),
				row.getScheduledChangeAt());
	}

	private static Money money(PricingCatalog.PriceInfo p) {
		return new Money(p.unitAmount(), p.currency());
	}

	private static String label(String key) {
		if (SubscriptionItem.SEAT_KEY.equals(key)) {
			return "Team members";
		}
		return BillingModule.fromKey(key).map(BillingModule::label).orElse(key);
	}
}
