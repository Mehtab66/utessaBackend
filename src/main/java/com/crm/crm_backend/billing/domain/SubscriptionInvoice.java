package com.crm.crm_backend.billing.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Invoice mirrored from Stripe. Amounts are in the currency's minor unit. */
@Getter
@Setter
@Entity
@Table(name = "subscription_invoices")
public class SubscriptionInvoice extends BillingAuditedEntity {

	@Column(name = "organization_id", nullable = false, updatable = false)
	private UUID organizationId;

	@Column(name = "subscription_id")
	private UUID subscriptionId;

	@Column(name = "stripe_invoice_id", nullable = false, updatable = false)
	private String stripeInvoiceId;

	@Column(name = "stripe_subscription_id")
	private String stripeSubscriptionId;

	@Column(name = "invoice_number")
	private String invoiceNumber;

	@Column(name = "billing_reason")
	private String billingReason;

	@Column(name = "amount_due", nullable = false)
	private long amountDue;

	@Column(name = "amount_paid", nullable = false)
	private long amountPaid;

	@Column(name = "amount_remaining", nullable = false)
	private long amountRemaining;

	@Column(nullable = false, length = 3)
	private String currency;

	/** Stripe's status: draft, open, paid, uncollectible, void. */
	@Column(name = "invoice_status", nullable = false)
	private String invoiceStatus;

	@Column(name = "hosted_invoice_url", columnDefinition = "TEXT")
	private String hostedInvoiceUrl;

	@Column(name = "invoice_pdf_url", columnDefinition = "TEXT")
	private String invoicePdfUrl;

	@Column(name = "period_start")
	private Instant periodStart;

	@Column(name = "period_end")
	private Instant periodEnd;

	@Column(name = "paid_at")
	private Instant paidAt;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "next_payment_attempt")
	private Instant nextPaymentAttempt;

	@Column(name = "stripe_created_at")
	private Instant stripeCreatedAt;
}
