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
import com.crm.crm_backend.billing.application.ChangePlanner.ItemChange;
import com.crm.crm_backend.billing.application.ChangePlanner.Kind;
import com.crm.crm_backend.billing.application.ChangePlanner.Plan;
import com.crm.crm_backend.billing.application.ChangePlanner.Target;
import com.crm.crm_backend.billing.domain.BillingInterval;

@ExtendWith(MockitoExtension.class)
class ChangePlannerTest {

	@Mock
	PricingCatalog catalog;

	private static final BillingInterval M = BillingInterval.MONTHLY;
	private static final BillingInterval Y = BillingInterval.YEARLY;

	/** CRM + 5 seats, monthly. */
	private final List<Current> crmFiveSeats = List.of(
			new Current("crm", "si_crm", priceId("crm", M), 1),
			new Current("seat", "si_seat", priceId("seat", M), 5));

	@BeforeEach
	void prices() {
		BillingTestData.stubCatalog(catalog);
	}

	private Plan plan(List<Current> current, BillingInterval interval, List<String> modules, int seats, BillingInterval to) {
		return ChangePlanner.plan(current, interval, new Target(new TreeSet<>(modules), seats, to), catalog);
	}

	@Test
	void upgradeAddsNewModulesAndUpdatesSeatItemById() {
		// The spec's example: CRM + 5 seats → all four modules + 8 seats.
		Plan p = plan(crmFiveSeats, M, List.of("crm", "ops", "mkt", "tc"), 8, M);

		assertThat(p.kind()).isEqualTo(Kind.UPGRADE);
		assertThat(p.addedModules()).containsExactly("mkt", "ops", "tc");
		assertThat(p.removedModules()).isEmpty();
		// Existing seat item is updated by its id (no new subscription item for seats)...
		assertThat(p.itemChanges()).contains(new ItemChange("si_seat", priceId("seat", M), 8L, false));
		// ...CRM is untouched, and only the three new modules are added as new items.
		assertThat(p.itemChanges()).noneMatch(c -> "si_crm".equals(c.stripeItemId()));
		assertThat(p.itemChanges().stream().filter(c -> c.stripeItemId() == null)).hasSize(3);
		assertThat(p.targetTotal()).isEqualTo(3000 + 4000 + 2500 + 2000 + 8 * 1000);
	}

	@Test
	void downgradeDeletesRemovedModuleItemsById() {
		List<Current> current = List.of(
				new Current("crm", "si_crm", priceId("crm", M), 1),
				new Current("ops", "si_ops", priceId("ops", M), 1),
				new Current("seat", "si_seat", priceId("seat", M), 5));
		Plan p = plan(current, M, List.of("crm"), 3, M);

		assertThat(p.kind()).isEqualTo(Kind.DOWNGRADE);
		assertThat(p.removedModules()).containsExactly("ops");
		assertThat(p.itemChanges()).containsExactlyInAnyOrder(
				new ItemChange("si_ops", null, null, true),
				new ItemChange("si_seat", priceId("seat", M), 3L, false));
	}

	@Test
	void zeroSeatsDeletesTheSeatItem() {
		Plan p = plan(crmFiveSeats, M, List.of("crm"), 0, M);
		assertThat(p.kind()).isEqualTo(Kind.DOWNGRADE);
		assertThat(p.itemChanges()).containsExactly(new ItemChange("si_seat", null, null, true));
		assertThat(p.targetLines()).extracting(ChangePlanner.Line::key).containsExactly("crm");
	}

	@Test
	void addingAndRemovingAtOnceIsMixed() {
		Plan p = plan(crmFiveSeats, M, List.of("ops"), 5, M);
		assertThat(p.kind()).isEqualTo(Kind.MIXED);
	}

	@Test
	void sameModulesAndSeatsIsNoChange() {
		Plan p = plan(crmFiveSeats, M, List.of("crm"), 5, M);
		assertThat(p.kind()).isEqualTo(Kind.NO_CHANGE);
		assertThat(p.itemChanges()).isEmpty();
	}

	@Test
	void intervalSwitchMovesEveryItemToTheYearlyPriceKeepingItemsAndQuantities() {
		Plan p = plan(crmFiveSeats, M, List.of("crm"), 5, Y);

		assertThat(p.kind()).isEqualTo(Kind.INTERVAL);
		assertThat(p.itemChanges()).containsExactlyInAnyOrder(
				new ItemChange("si_crm", priceId("crm", Y), 1L, false),
				new ItemChange("si_seat", priceId("seat", Y), 5L, false));
		assertThat(p.targetTotal()).isEqualTo(30000 + 5 * 10000);
	}
}
