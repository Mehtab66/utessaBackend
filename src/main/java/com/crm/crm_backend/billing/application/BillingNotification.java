package com.crm.crm_backend.billing.application;

import java.time.Instant;
import java.util.UUID;

/**
 * A billing event the practice should hear about. Published as a Spring application event;
 * {@link BillingEmailNotifier} emails the owner, and other listeners (in-app alerts, Slack...)
 * can subscribe without touching billing code.
 */
public record BillingNotification(
		Type type,
		UUID organizationId,
		/** Relevant date: trial end, grace-period end, cancellation date... */
		Instant date,
		/** Amount in minor units, when relevant. */
		Long amount,
		String currency,
		/** Link to pay or view the invoice, when relevant. */
		String url,
		/** For TRIAL_ENDING: whether a card is on file so billing starts automatically. */
		boolean hasPaymentMethod
) {

	public enum Type {
		TRIAL_ENDING,
		PAYMENT_FAILED,
		/** The payment for an upgrade failed; the change wasn't applied. */
		CHANGE_PAYMENT_FAILED,
		PAYMENT_RECOVERED,
		SUBSCRIPTION_CANCELED
	}
}
