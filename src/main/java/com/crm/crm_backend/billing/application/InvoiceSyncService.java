package com.crm.crm_backend.billing.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.crm.crm_backend.billing.domain.OrganizationSubscription;
import com.crm.crm_backend.billing.domain.SubscriptionInvoice;
import com.crm.crm_backend.billing.infrastructure.OrganizationSubscriptionRepository;
import com.crm.crm_backend.billing.infrastructure.SubscriptionInvoiceRepository;
import com.stripe.model.Invoice;

/**
 * Mirrors Stripe invoices into {@code subscription_invoices}. Always given an invoice freshly read
 * from Stripe, so whichever webhook arrives last, the stored copy is Stripe's current state.
 */
@Service
public class InvoiceSyncService {

	private final SubscriptionInvoiceRepository invoices;
	private final OrganizationSubscriptionRepository subscriptions;
	private final TransactionTemplate tx;

	public InvoiceSyncService(SubscriptionInvoiceRepository invoices, OrganizationSubscriptionRepository subscriptions,
			PlatformTransactionManager txManager) {
		this.invoices = invoices;
		this.subscriptions = subscriptions;
		this.tx = new TransactionTemplate(txManager);
	}

	/** The organization an invoice belongs to (by its Stripe customer). */
	public Optional<OrganizationSubscription> owner(Invoice inv) {
		return inv.getCustomer() == null ? Optional.empty() : subscriptions.findByStripeCustomerId(inv.getCustomer());
	}

	/** Inserts or updates the invoice. Returns the stored row, or empty if no organization matches. */
	public Optional<SubscriptionInvoice> upsert(Invoice inv) {
		OrganizationSubscription owner = owner(inv).orElse(null);
		if (owner == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(tx.execute(s -> write(owner, inv)));
		} catch (DataIntegrityViolationException raced) {
			// Two webhooks for the same new invoice: the other one inserted it first; update it instead.
			return Optional.of(tx.execute(s -> write(owner, inv)));
		}
	}

	private SubscriptionInvoice write(OrganizationSubscription owner, Invoice inv) {
		SubscriptionInvoice row = invoices.findByStripeInvoiceId(inv.getId()).orElseGet(SubscriptionInvoice::new);
		UUID orgId = owner.getOrganizationId();
		row.setOrganizationId(orgId);
		row.setSubscriptionId(owner.getId());
		row.setStripeInvoiceId(inv.getId());
		String subId = inv.getParent() != null && inv.getParent().getSubscriptionDetails() != null
				? inv.getParent().getSubscriptionDetails().getSubscription() : null;
		row.setStripeSubscriptionId(subId);
		row.setInvoiceNumber(inv.getNumber());
		row.setBillingReason(inv.getBillingReason());
		row.setAmountDue(nz(inv.getAmountDue()));
		row.setAmountPaid(nz(inv.getAmountPaid()));
		row.setAmountRemaining(nz(inv.getAmountRemaining()));
		row.setCurrency(inv.getCurrency() == null ? "usd" : inv.getCurrency());
		row.setInvoiceStatus(inv.getStatus() == null ? "draft" : inv.getStatus());
		row.setHostedInvoiceUrl(inv.getHostedInvoiceUrl());
		row.setInvoicePdfUrl(inv.getInvoicePdf());
		row.setPeriodStart(SubscriptionSyncService.instant(inv.getPeriodStart()));
		row.setPeriodEnd(SubscriptionSyncService.instant(inv.getPeriodEnd()));
		row.setPaidAt(inv.getStatusTransitions() == null ? null : SubscriptionSyncService.instant(inv.getStatusTransitions().getPaidAt()));
		row.setAttemptCount(inv.getAttemptCount() == null ? 0 : inv.getAttemptCount().intValue());
		row.setNextPaymentAttempt(SubscriptionSyncService.instant(inv.getNextPaymentAttempt()));
		row.setStripeCreatedAt(SubscriptionSyncService.instant(inv.getCreated()));
		if (row.getAmountPaid() > 0 && owner.getPaidBillingStartedAt() == null) {
			// A paid invoice means paid billing has started (covers a subscription cancelled before we synced it).
			subscriptions.findById(owner.getId()).ifPresent(o -> {
				if (o.getPaidBillingStartedAt() == null) {
					o.setPaidBillingStartedAt(row.getPaidAt() != null ? row.getPaidAt() : java.time.Instant.now());
					subscriptions.save(o);
				}
			});
		}
		return invoices.saveAndFlush(row);
	}

	private static long nz(Long v) {
		return v == null ? 0L : v;
	}
}
