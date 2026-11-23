package com.crm.crm_backend.billing.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.util.Map;
import java.util.Optional;

import com.crm.crm_backend.billing.domain.BillingInterval;
import com.crm.crm_backend.billing.domain.BillingModule;

/** Shared fixtures for billing tests. Prices mirror a typical setup but are test values only. */
public final class BillingTestData {

	private BillingTestData() {
	}

	public static final Map<String, long[]> AMOUNTS = Map.of(
			"crm", new long[] { 3000, 30000 },
			"ops", new long[] { 4000, 40000 },
			"mkt", new long[] { 2500, 25000 },
			"tc", new long[] { 2000, 20000 },
			PricingCatalog.SEAT, new long[] { 1000, 10000 });

	public static String priceId(String key, BillingInterval interval) {
		return "price_" + key + "_" + (interval == BillingInterval.YEARLY ? "y" : "m");
	}

	public static PricingCatalog.PriceInfo price(String key, BillingInterval interval) {
		long amount = AMOUNTS.get(key)[interval == BillingInterval.YEARLY ? 1 : 0];
		return new PricingCatalog.PriceInfo(key, interval, priceId(key, interval), "prod_" + key, key, amount, "usd");
	}

	/** Makes a (mocked) catalog answer like the real one for the test prices above. */
	public static void stubCatalog(PricingCatalog catalog) {
		lenient().when(catalog.modulePrice(any(), any())).thenAnswer(inv -> {
			BillingModule m = inv.getArgument(0);
			return price(m.key(), inv.getArgument(1));
		});
		lenient().when(catalog.seatPrice(any())).thenAnswer(inv -> price(PricingCatalog.SEAT, inv.getArgument(0)));
		lenient().when(catalog.currency()).thenReturn("usd");
		lenient().when(catalog.byPriceId(anyString())).thenAnswer(inv -> {
			String id = inv.getArgument(0);
			for (String key : AMOUNTS.keySet()) {
				for (BillingInterval i : BillingInterval.values()) {
					if (priceId(key, i).equals(id)) {
						return Optional.of(price(key, i));
					}
				}
			}
			return Optional.empty();
		});
	}

	public static StripeProperties props() {
		return new StripeProperties("sk_test_x", "pk_test_x", "whsec_test_x", "usd", 14, 10, 7,
				"http://localhost:5173", 0, 0, null);
	}
}
