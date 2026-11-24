package com.crm.crm_backend.billing.application;

import static com.crm.crm_backend.billing.application.BillingTestData.priceId;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.crm.crm_backend.billing.application.ChangePlanner.Current;
import com.crm.crm_backend.billing.application.ChangePlanner.Kind;
import com.crm.crm_backend.billing.application.ChangePlanner.Line;
import com.crm.crm_backend.billing.application.ChangePlanner.Plan;
import com.crm.crm_backend.billing.application.ChangePlanner.Target;
import com.crm.crm_backend.billing.domain.BillingInterval;
import com.stripe.model.SubscriptionSchedule;

/**
 * A second change booked for the next renewal must build on the first one (it replaces the booked
 * phase as a whole), not on today's plan — otherwise the first booking is silently undone.
 */
@ExtendWith(MockitoExtension.class)
class BookedChangeTest {

	private static final BillingInterval M = BillingInterval.MONTHLY;
	private static final long PERIOD_START = 1_700_000_000L;
	private static final long PERIOD_END = PERIOD_START + 30 * 86_400L;

	@Mock
	PricingCatalog catalog;

	@BeforeEach
	void prices() {
		BillingTestData.stubCatalog(catalog);
	}

	@Test
	void readsThePhaseThatStartsAtRenewal() {
		SubscriptionSchedule schedule = schedule(
				phase(PERIOD_START, PERIOD_END, item("crm", 1), item("mkt", 1), item("seat", 5)),
				phase(PERIOD_END, null, item("crm", 1), item("seat", 5)));

		List<Current> booked = SubscriptionChangeService.bookedPhase(schedule, catalog);

		assertThat(booked).extracting(Current::key, Current::quantity)
				.containsExactly(org.assertj.core.groups.Tuple.tuple("crm", 1), org.assertj.core.groups.Tuple.tuple("seat", 5));
	}

	@Test
	void noBookedPhaseOnceTheScheduleIsInItsLastPhase() {
		SubscriptionSchedule schedule = schedule(phase(PERIOD_START, PERIOD_END, item("crm", 1)));

		assertThat(SubscriptionChangeService.bookedPhase(schedule, catalog)).isNull();
	}

	@Test
	void fewerSeatsOnTopOfABookedModuleRemovalKeepsTheRemoval() {
		// Today: CRM + Marketing + 5 seats. Booked for renewal: Marketing removed.
		List<Current> booked = SubscriptionChangeService.bookedPhase(schedule(
				phase(PERIOD_START, PERIOD_END, item("crm", 1), item("mkt", 1), item("seat", 5)),
				phase(PERIOD_END, null, item("crm", 1), item("seat", 5))), catalog);

		// Seats-only request (modules omitted → taken from the booked plan): 5 → 3.
		Plan p = ChangePlanner.plan(booked, M, new Target(new TreeSet<>(List.of("crm")), 3, M), catalog);

		assertThat(p.kind()).isEqualTo(Kind.DOWNGRADE);
		assertThat(p.addedModules()).isEmpty();
		assertThat(p.removedModules()).isEmpty();
		assertThat(p.targetLines()).extracting(Line::key).containsExactly("crm", "seat");
		assertThat(p.targetTotal()).isEqualTo(3000 + 3 * 1000); // not 8500: Marketing stays removed
	}

	// ── Stripe model builders ──────────────────────────────────────────

	private static SubscriptionSchedule schedule(SubscriptionSchedule.Phase... phases) {
		SubscriptionSchedule s = new SubscriptionSchedule();
		SubscriptionSchedule.CurrentPhase current = new SubscriptionSchedule.CurrentPhase();
		current.setStartDate(phases[0].getStartDate());
		current.setEndDate(phases[0].getEndDate());
		s.setCurrentPhase(current);
		s.setPhases(List.of(phases));
		return s;
	}

	private static SubscriptionSchedule.Phase phase(long start, Long end, SubscriptionSchedule.Phase.Item... items) {
		SubscriptionSchedule.Phase p = new SubscriptionSchedule.Phase();
		p.setStartDate(start);
		p.setEndDate(end);
		p.setItems(List.of(items));
		return p;
	}

	private static SubscriptionSchedule.Phase.Item item(String key, long quantity) {
		SubscriptionSchedule.Phase.Item i = new SubscriptionSchedule.Phase.Item();
		i.setPrice(priceId(key, M));
		i.setQuantity(quantity);
		return i;
	}
}
