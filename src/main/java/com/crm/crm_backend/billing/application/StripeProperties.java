package com.crm.crm_backend.billing.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.crm.crm_backend.billing.domain.BillingInterval;
import com.crm.crm_backend.billing.domain.BillingModule;

/**
 * All Stripe and billing settings in one place ({@code billing.*} in application.properties,
 * values from the git-ignored .env). Nothing else reads Stripe configuration directly.
 *
 * <p>Price references may be a Price ID ({@code price_...}) or a Product ID ({@code prod_...});
 * a Product ID resolves to that product's default price (see {@link PricingCatalog}).
 */
@ConfigurationProperties(prefix = "billing")
public record StripeProperties(
		String secretKey,
		String publishableKey,
		String webhookSecret,
		String currency,
		/** Free trial length; the same value the signup flow uses for the organization trial. */
		int trialDays,
		/** Team members (besides the owner) a practice may have during the free trial. */
		int trialMaxMembers,
		/** Days a practice keeps paid access after a failed payment before access is restricted. */
		int gracePeriodDays,
		/** Base URL of the Utessa frontend; Stripe Checkout returns the customer here. */
		String frontendUrl,
		int connectTimeoutMs,
		int readTimeoutMs,
		Prices prices
) {

	public StripeProperties {
		currency = currency == null || currency.isBlank() ? "usd" : currency.trim().toLowerCase();
		trialDays = trialDays <= 0 ? 14 : trialDays;
		trialMaxMembers = trialMaxMembers <= 0 ? 10 : trialMaxMembers;
		gracePeriodDays = gracePeriodDays < 0 ? 7 : gracePeriodDays;
		connectTimeoutMs = connectTimeoutMs <= 0 ? 10_000 : connectTimeoutMs;
		readTimeoutMs = readTimeoutMs <= 0 ? 30_000 : readTimeoutMs;
		prices = prices == null ? new Prices(null, null, null, null, null) : prices;
	}

	/** True when a secret key is present, i.e. Stripe calls can be made. */
	public boolean configured() {
		return secretKey != null && !secretKey.isBlank() && !secretKey.startsWith("${");
	}

	public boolean webhookConfigured() {
		return webhookSecret != null && !webhookSecret.isBlank() && !webhookSecret.startsWith("${");
	}

	public record Prices(
			IntervalPrices crm,
			IntervalPrices ops,
			IntervalPrices mkt,
			IntervalPrices tc,
			/** Team-member seat price (per member). */
			IntervalPrices seat
	) {
		public IntervalPrices forModule(BillingModule module) {
			return switch (module) {
				case CRM -> crm;
				case OPERATIONS -> ops;
				case MARKETING -> mkt;
				case TIME_CLOCK -> tc;
			};
		}
	}

	public record IntervalPrices(String monthly, String yearly) {
		public String forInterval(BillingInterval interval) {
			return interval == BillingInterval.YEARLY ? yearly : monthly;
		}
	}
}
