package com.crm.crm_backend.billing.domain;

/** Billing cycle of the whole subscription. Every item on one subscription shares it. */
public enum BillingInterval {
	MONTHLY("month"),
	YEARLY("year");

	private final String stripeInterval;

	BillingInterval(String stripeInterval) {
		this.stripeInterval = stripeInterval;
	}

	/** The value Stripe uses in {@code price.recurring.interval}. */
	public String stripeInterval() {
		return stripeInterval;
	}
}
