package com.crm.crm_backend.billing.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import com.crm.crm_backend.billing.domain.BillingInterval;
import com.crm.crm_backend.billing.domain.BillingModule;
import com.crm.crm_backend.billing.domain.SubscriptionItem;

/**
 * Works out how to turn the current subscription into the requested one. Pure logic (no Stripe,
 * no database) so it can be unit-tested. Existing Stripe subscription items are always updated
 * by their item id; only genuinely new modules get new items — never a second subscription.
 */
public final class ChangePlanner {

	private ChangePlanner() {
	}

	public enum Kind {
		NO_CHANGE,
		/** Only additions: new modules and/or more seats. */
		UPGRADE,
		/** Only reductions: fewer modules and/or fewer seats. */
		DOWNGRADE,
		/** Additions and reductions at once (done as two separate changes). */
		MIXED,
		/** Monthly ↔ yearly (modules and seats preserved). */
		INTERVAL
	}

	/** A line the subscription should have afterwards. */
	public record Line(String key, String priceId, long quantity, long unitAmount, String currency) {
		public long total() {
			return quantity * unitAmount;
		}
	}

	/** A line the subscription has now (from Stripe, via the local mirror). */
	public record Current(String key, String stripeItemId, String priceId, int quantity) {
	}

	/** One change to send to Stripe: update an item by id, delete it, or add a new one. */
	public record ItemChange(String stripeItemId, String priceId, Long quantity, boolean deleted) {
	}

	public record Target(SortedSet<String> moduleKeys, int seats, BillingInterval interval) {
	}

	public record Plan(
			Kind kind,
			Target target,
			List<Line> targetLines,
			List<ItemChange> itemChanges,
			SortedSet<String> addedModules,
			SortedSet<String> removedModules,
			int seatsFrom,
			int seatsTo,
			BillingInterval intervalFrom
	) {
		public long targetTotal() {
			return targetLines.stream().mapToLong(Line::total).sum();
		}

		public boolean intervalChanged() {
			return intervalFrom != target.interval();
		}
	}

	public static Plan plan(Collection<Current> current, BillingInterval currentInterval, Target target, PricingCatalog catalog) {
		// What the subscription should contain afterwards, priced from the server-side catalog.
		List<Line> lines = new ArrayList<>();
		for (String key : target.moduleKeys()) {
			BillingModule module = BillingModule.fromKey(key).orElseThrow();
			PricingCatalog.PriceInfo p = catalog.modulePrice(module, target.interval());
			lines.add(new Line(key, p.priceId(), 1, p.unitAmount(), p.currency()));
		}
		if (target.seats() > 0) {
			PricingCatalog.PriceInfo p = catalog.seatPrice(target.interval());
			lines.add(new Line(SubscriptionItem.SEAT_KEY, p.priceId(), target.seats(), p.unitAmount(), p.currency()));
		}

		Map<String, Current> byKey = new LinkedHashMap<>();
		current.forEach(c -> byKey.put(c.key(), c));
		Map<String, Line> targetByKey = new LinkedHashMap<>();
		lines.forEach(l -> targetByKey.put(l.key(), l));

		// Item-level changes: keep item ids for modules that stay, add new ones, delete dropped ones.
		List<ItemChange> changes = new ArrayList<>();
		for (Current c : byKey.values()) {
			Line l = targetByKey.get(c.key());
			if (l == null) {
				changes.add(new ItemChange(c.stripeItemId(), null, null, true));
			} else if (!l.priceId().equals(c.priceId()) || l.quantity() != c.quantity()) {
				changes.add(new ItemChange(c.stripeItemId(), l.priceId(), l.quantity(), false));
			}
		}
		for (Line l : lines) {
			if (!byKey.containsKey(l.key())) {
				changes.add(new ItemChange(null, l.priceId(), l.quantity(), false));
			}
		}

		SortedSet<String> currentModules = new TreeSet<>();
		byKey.keySet().stream().filter(k -> !SubscriptionItem.SEAT_KEY.equals(k)).forEach(currentModules::add);
		SortedSet<String> added = new TreeSet<>(target.moduleKeys());
		added.removeAll(currentModules);
		SortedSet<String> removed = new TreeSet<>(currentModules);
		removed.removeAll(target.moduleKeys());
		int seatsFrom = byKey.containsKey(SubscriptionItem.SEAT_KEY) ? byKey.get(SubscriptionItem.SEAT_KEY).quantity() : 0;
		int seatsTo = target.seats();

		Kind kind;
		if (currentInterval != null && currentInterval != target.interval()) {
			kind = Kind.INTERVAL;
		} else {
			boolean adds = !added.isEmpty() || seatsTo > seatsFrom;
			boolean cuts = !removed.isEmpty() || seatsTo < seatsFrom;
			kind = adds && cuts ? Kind.MIXED : adds ? Kind.UPGRADE : cuts ? Kind.DOWNGRADE : Kind.NO_CHANGE;
		}
		return new Plan(kind, target, List.copyOf(lines), List.copyOf(changes), added, removed, seatsFrom, seatsTo,
				currentInterval == null ? target.interval() : currentInterval);
	}
}
