package com.crm.crm_backend.billing.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.crm.crm_backend.billing.domain.BillingInterval;
import com.crm.crm_backend.billing.domain.BillingModule;
import com.crm.crm_backend.shared.exception.ApiException;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.param.PriceRetrieveParams;
import com.stripe.param.ProductRetrieveParams;

/**
 * The server-side price list. Prices are configured as Stripe Price or Product IDs and their
 * amounts are read from Stripe — nothing about pricing is hard-coded. Every configured price is
 * validated (active, recurring, licensed, right interval, right currency) before it is used, and
 * the frontend can only ever pick a module / seat count / interval: it never sends a price ID.
 */
@Component
public class PricingCatalog {

	private static final Logger log = LoggerFactory.getLogger(PricingCatalog.class);
	/** Re-read prices from Stripe this often so Dashboard changes are picked up. */
	private static final Duration REFRESH_AFTER = Duration.ofHours(1);
	/** After a failed load, wait this long before trying Stripe again. */
	private static final Duration RETRY_AFTER = Duration.ofMinutes(1);

	/** "seat" is the team-member product. */
	public static final String SEAT = "seat";

	public record PriceInfo(
			String productKey,
			BillingInterval interval,
			String priceId,
			String productId,
			String productName,
			long unitAmount,
			String currency
	) {
	}

	private record Snapshot(
			Map<BillingInterval, Map<BillingModule, PriceInfo>> modules,
			Map<BillingInterval, PriceInfo> seats,
			Map<String, PriceInfo> byPriceId,
			Instant loadedAt
	) {
	}

	private final StripeProperties props;
	private final StripeClient stripe;
	private volatile Snapshot snapshot;
	private volatile String lastError;
	private volatile Instant lastAttempt = Instant.EPOCH;

	public PricingCatalog(StripeProperties props, StripeClient stripe) {
		this.props = props;
		this.stripe = stripe;
	}

	/** Loads and validates prices at startup. Failure is logged, not fatal: the CRM keeps running. */
	@EventListener(ApplicationReadyEvent.class)
	public void warmUp() {
		if (!props.configured()) {
			return;
		}
		try {
			load();
			log.info("Billing price list loaded and validated ({} prices).", snapshot.byPriceId().size());
		} catch (RuntimeException ex) {
			log.error("Billing price list could not be loaded: {}", ex.getMessage());
		}
	}

	public PriceInfo modulePrice(BillingModule module, BillingInterval interval) {
		return current().modules().get(interval).get(module);
	}

	public PriceInfo seatPrice(BillingInterval interval) {
		return current().seats().get(interval);
	}

	/** Looks up a configured price by its Stripe ID (used when syncing subscriptions from Stripe). */
	public Optional<PriceInfo> byPriceId(String priceId) {
		return Optional.ofNullable(current().byPriceId().get(priceId));
	}

	public List<PriceInfo> all() {
		return List.copyOf(current().byPriceId().values());
	}

	public String currency() {
		return props.currency();
	}

	/** Forces a reload on the next access (e.g. after a price changed in Stripe). */
	public void invalidate() {
		snapshot = null;
		lastAttempt = Instant.EPOCH;
	}

	private Snapshot current() {
		if (!props.configured()) {
			throw unavailable("Billing is not set up on this server yet.");
		}
		Snapshot s = snapshot;
		if (s != null && s.loadedAt().plus(REFRESH_AFTER).isAfter(Instant.now())) {
			return s;
		}
		synchronized (this) {
			s = snapshot;
			if (s != null && s.loadedAt().plus(REFRESH_AFTER).isAfter(Instant.now())) {
				return s;
			}
			if (s == null && lastError != null && lastAttempt.plus(RETRY_AFTER).isAfter(Instant.now())) {
				throw unavailable("Billing prices are unavailable right now. Please try again shortly.");
			}
			try {
				load();
			} catch (RuntimeException ex) {
				if (s != null) {
					// Keep serving the last good price list if a refresh fails.
					log.warn("Billing price refresh failed, keeping previous list: {}", ex.getMessage());
					return s;
				}
				throw unavailable("Billing prices are unavailable right now. Please try again shortly.");
			}
			return snapshot;
		}
	}

	private void load() {
		lastAttempt = Instant.now();
		try {
			StripeProperties.Prices cfg = props.prices();
			Map<BillingInterval, Map<BillingModule, PriceInfo>> modules = new EnumMap<>(BillingInterval.class);
			Map<BillingInterval, PriceInfo> seats = new EnumMap<>(BillingInterval.class);
			Map<String, PriceInfo> byId = new java.util.LinkedHashMap<>();
			List<String> problems = new ArrayList<>();
			for (BillingInterval interval : BillingInterval.values()) {
				Map<BillingModule, PriceInfo> perModule = new EnumMap<>(BillingModule.class);
				for (BillingModule module : BillingModule.values()) {
					StripeProperties.IntervalPrices ip = cfg.forModule(module);
					PriceInfo info = resolve(module.key(), interval, ip == null ? null : ip.forInterval(interval), problems);
					if (info != null) {
						perModule.put(module, info);
						byId.put(info.priceId(), info);
					}
				}
				modules.put(interval, Collections.unmodifiableMap(perModule));
				PriceInfo seat = resolve(SEAT, interval, cfg.seat() == null ? null : cfg.seat().forInterval(interval), problems);
				if (seat != null) {
					seats.put(interval, seat);
					byId.put(seat.priceId(), seat);
				}
			}
			if (!problems.isEmpty()) {
				throw new IllegalStateException("Invalid billing price configuration: " + String.join("; ", problems));
			}
			snapshot = new Snapshot(modules, seats, Collections.unmodifiableMap(byId), Instant.now());
			lastError = null;
		} catch (RuntimeException ex) {
			lastError = ex.getMessage();
			throw ex;
		}
	}

	private PriceInfo resolve(String key, BillingInterval interval, String ref, List<String> problems) {
		String label = key + " " + interval.name().toLowerCase();
		if (ref == null || ref.isBlank()) {
			problems.add(label + ": not configured");
			return null;
		}
		String id = ref.trim();
		try {
			Price price;
			String productName = null;
			if (id.startsWith("prod_")) {
				Product product = stripe.v1().products().retrieve(id,
						ProductRetrieveParams.builder().addExpand("default_price").build());
				if (!Boolean.TRUE.equals(product.getActive())) {
					problems.add(label + ": product " + id + " is archived");
					return null;
				}
				price = product.getDefaultPriceObject();
				if (price == null) {
					problems.add(label + ": product " + id + " has no default price");
					return null;
				}
				productName = product.getName();
			} else if (id.startsWith("price_")) {
				price = stripe.v1().prices().retrieve(id,
						PriceRetrieveParams.builder().addExpand("product").build());
				if (price.getProductObject() != null) {
					productName = price.getProductObject().getName();
				}
			} else {
				problems.add(label + ": '" + id + "' is neither a price_ nor a prod_ ID");
				return null;
			}
			if (!Boolean.TRUE.equals(price.getActive())) {
				problems.add(label + ": price " + price.getId() + " is inactive");
				return null;
			}
			if (price.getRecurring() == null) {
				problems.add(label + ": price " + price.getId() + " is not recurring");
				return null;
			}
			if (!interval.stripeInterval().equals(price.getRecurring().getInterval())
					|| (price.getRecurring().getIntervalCount() != null && price.getRecurring().getIntervalCount() != 1L)) {
				problems.add(label + ": price " + price.getId() + " bills every " + price.getRecurring().getIntervalCount()
						+ " " + price.getRecurring().getInterval() + ", expected 1 " + interval.stripeInterval());
				return null;
			}
			if (!"licensed".equals(price.getRecurring().getUsageType())) {
				problems.add(label + ": price " + price.getId() + " is metered; a per-unit licensed price is required");
				return null;
			}
			if (!props.currency().equalsIgnoreCase(price.getCurrency())) {
				problems.add(label + ": price " + price.getId() + " is in " + price.getCurrency() + ", expected " + props.currency());
				return null;
			}
			if (price.getUnitAmount() == null) {
				problems.add(label + ": price " + price.getId() + " has no fixed unit amount (tiered prices aren't supported)");
				return null;
			}
			return new PriceInfo(key, interval, price.getId(), price.getProduct(), productName,
					price.getUnitAmount(), price.getCurrency());
		} catch (StripeException ex) {
			String reason = ex.getStripeError() != null && ex.getStripeError().getMessage() != null
					? ex.getStripeError().getMessage() : ex.getMessage();
			problems.add(label + ": Stripe error for " + id + " (" + reason + ")");
			return null;
		}
	}

	private static ApiException unavailable(String message) {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message);
	}
}
