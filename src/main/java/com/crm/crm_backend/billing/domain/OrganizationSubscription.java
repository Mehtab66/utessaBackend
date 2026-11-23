package com.crm.crm_backend.billing.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * The organization's billing record: its Stripe customer and its single Stripe subscription.
 * Exactly one row per organization (unique {@code organization_id}). Fields mirror Stripe and are
 * written only from Stripe responses or verified webhooks, never from what the browser says.
 */
@Getter
@Setter
@Entity
@Table(name = "organization_subscriptions")
public class OrganizationSubscription extends BillingAuditedEntity {

	/** Value of {@link #subscriptionStatus} before any Stripe subscription exists. */
	public static final String STATUS_NONE = "none";

	@Column(name = "organization_id", nullable = false, updatable = false)
	private UUID organizationId;

	@Column(name = "stripe_customer_id")
	private String stripeCustomerId;

	@Column(name = "stripe_subscription_id")
	private String stripeSubscriptionId;

	/** Subscription schedule holding a change booked for the next renewal. */
	@Column(name = "stripe_schedule_id")
	private String stripeScheduleId;

	/** Stripe's subscription status in lower case, or {@code none}. */
	@Column(name = "subscription_status", nullable = false)
	private String subscriptionStatus = STATUS_NONE;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "billing_interval", columnDefinition = "billing_interval")
	private BillingInterval billingInterval;

	@Column(name = "trial_start")
	private Instant trialStart;

	@Column(name = "trial_end")
	private Instant trialEnd;

	@Column(name = "current_period_start")
	private Instant currentPeriodStart;

	@Column(name = "current_period_end")
	private Instant currentPeriodEnd;

	@Column(name = "cancel_at_period_end", nullable = false)
	private boolean cancelAtPeriodEnd;

	@Column(name = "cancel_at")
	private Instant cancelAt;

	@Column(name = "canceled_at")
	private Instant canceledAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "scheduled_interval", columnDefinition = "billing_interval")
	private BillingInterval scheduledInterval;

	@Column(name = "scheduled_change_at")
	private Instant scheduledChangeAt;

	/** First time paid billing started; from then on the free trial is over for good. */
	@Column(name = "paid_billing_started_at")
	private Instant paidBillingStartedAt;

	@Column(name = "payment_failed_at")
	private Instant paymentFailedAt;

	@Column(name = "grace_period_ends_at")
	private Instant gracePeriodEndsAt;

	/** Open Stripe Checkout session; expired before a new one is created (never two at once). */
	@Column(name = "pending_checkout_session_id")
	private String pendingCheckoutSessionId;

	@Column(name = "pending_checkout_expires_at")
	private Instant pendingCheckoutExpiresAt;

	@Column(name = "latest_invoice_id")
	private String latestInvoiceId;

	@Column(name = "latest_invoice_status")
	private String latestInvoiceStatus;

	/** Stripe time of the newest state applied here; older data is never applied over it. */
	@Column(name = "stripe_synced_at")
	private Instant stripeSyncedAt;

	/** Optimistic lock: an API change and a webhook can't silently overwrite each other. */
	@Version
	@Column(nullable = false)
	private long version;
}
