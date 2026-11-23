package com.crm.crm_backend.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.crm.crm_backend.billing.application.EntitlementService.Entitlements;
import com.crm.crm_backend.billing.application.EntitlementService.State;
import com.crm.crm_backend.billing.domain.BillingInterval;
import com.crm.crm_backend.billing.domain.BillingModule;
import com.crm.crm_backend.billing.domain.OrganizationSubscription;
import com.crm.crm_backend.billing.domain.SubscriptionItem;
import com.crm.crm_backend.billing.domain.SubscriptionItemStatus;
import com.crm.crm_backend.billing.infrastructure.OrganizationSubscriptionRepository;
import com.crm.crm_backend.billing.infrastructure.SubscriptionItemRepository;
import com.crm.crm_backend.organization.domain.Organization;
import com.crm.crm_backend.organization.infrastructure.OrganizationRepository;
import com.crm.crm_backend.shared.exception.ApiException;

@ExtendWith(MockitoExtension.class)
class EntitlementServiceTest {

	@Mock
	OrganizationRepository organizations;
	@Mock
	OrganizationSubscriptionRepository subscriptions;
	@Mock
	SubscriptionItemRepository items;
	@Mock
	BillingAccess access;

	EntitlementService service;
	final UUID orgId = UUID.randomUUID();
	final Instant now = Instant.now();
	Organization org;
	OrganizationSubscription row;
	final List<SubscriptionItem> subscriptionItems = new ArrayList<>();

	@BeforeEach
	void setUp() {
		service = new EntitlementService(organizations, subscriptions, items, access, BillingTestData.props());
		org = new Organization();
		org.setId(orgId);
		org.setTrialStartsAt(now.minus(Duration.ofDays(3)));
		org.setTrialEndsAt(now.plus(Duration.ofDays(11)));
		lenient().when(organizations.findById(orgId)).thenReturn(Optional.of(org));
		lenient().when(subscriptions.findByOrganizationId(orgId)).thenAnswer(inv -> Optional.ofNullable(row));
		lenient().when(items.findBySubscriptionIdAndStatusIn(any(), any())).thenAnswer(inv -> {
			List<SubscriptionItemStatus> wanted = inv.getArgument(1);
			return subscriptionItems.stream().filter(i -> wanted.contains(i.getStatus())).toList();
		});
		lenient().when(access.billableMembers(eq(orgId))).thenReturn(0);
	}

	private void subscription(String status, String... moduleKeys) {
		row = new OrganizationSubscription();
		row.setId(UUID.randomUUID());
		row.setOrganizationId(orgId);
		row.setStripeSubscriptionId("sub_test");
		row.setSubscriptionStatus(status);
		row.setCurrentPeriodStart(now.minus(Duration.ofDays(2)));
		row.setCurrentPeriodEnd(now.plus(Duration.ofDays(28)));
		for (String k : moduleKeys) {
			item(k, 1, SubscriptionItemStatus.ACTIVE);
		}
	}

	private void item(String key, int qty, SubscriptionItemStatus status) {
		SubscriptionItem i = new SubscriptionItem();
		i.setSubscriptionId(row.getId());
		i.setModuleKey(key);
		i.setQuantity(qty);
		i.setStatus(status);
		i.setBillingInterval(BillingInterval.MONTHLY);
		subscriptionItems.add(i);
	}

	private Entitlements get() {
		service.evict(orgId);
		return service.forOrganization(orgId);
	}

	@Test
	void newPracticeIsInTrialWithAllModulesAndTenMembers() {
		Entitlements e = get();
		assertThat(e.state()).isEqualTo(State.TRIAL);
		assertThat(e.modules()).containsExactlyInAnyOrder("crm", "ops", "mkt", "tc");
		assertThat(e.maxMembers()).isEqualTo(10);
		assertThat(e.accessEndsAt()).isEqualTo(org.getTrialEndsAt());
	}

	@Test
	void trialAccessIsSeparateFromPaidChoiceWhileTrialing() {
		// Trialing subscription for CRM only: during the trial every module is still available.
		subscription("trialing", "crm");
		row.setTrialEnd(now.plus(Duration.ofDays(5)));
		Entitlements e = get();
		assertThat(e.state()).isEqualTo(State.TRIAL);
		assertThat(e.modules()).hasSize(4);
		assertThat(e.trialEndsAt()).isEqualTo(row.getTrialEnd());
	}

	@Test
	void expiredTrialWithoutSubscriptionIsInactive() {
		org.setTrialEndsAt(now.minus(Duration.ofMinutes(1)));
		Entitlements e = get();
		assertThat(e.state()).isEqualTo(State.INACTIVE);
		assertThat(e.modules()).isEmpty();
		assertThatThrownBy(() -> service.requireModule(orgId, BillingModule.CRM))
				.isInstanceOf(ApiException.class).hasMessageContaining("free trial has ended");
	}

	@Test
	void activeSubscriptionGrantsOnlyPaidModulesAndSeats() {
		subscription("active", "crm", "ops");
		item(SubscriptionItem.SEAT_KEY, 5, SubscriptionItemStatus.ACTIVE);
		row.setPaidBillingStartedAt(now.minus(Duration.ofDays(2)));
		Entitlements e = get();
		assertThat(e.state()).isEqualTo(State.ACTIVE);
		assertThat(e.modules()).containsExactlyInAnyOrder("crm", "ops");
		assertThat(e.maxMembers()).isEqualTo(5);
		assertThatThrownBy(() -> service.requireModule(orgId, BillingModule.TIME_CLOCK))
				.isInstanceOf(ApiException.class).hasMessageContaining("isn't part of your plan");
	}

	@Test
	void moduleBookedForRemovalStaysUsableUntilRenewal() {
		subscription("active", "crm");
		item("ops", 1, SubscriptionItemStatus.SCHEDULED_REMOVAL);
		item("mkt", 1, SubscriptionItemStatus.SCHEDULED_ADD);
		Entitlements e = get();
		assertThat(e.modules()).containsExactlyInAnyOrder("crm", "ops");
	}

	@Test
	void pastDueKeepsAccessDuringGraceThenRestricts() {
		subscription("past_due", "crm");
		row.setPaymentFailedAt(now.minus(Duration.ofDays(1)));
		row.setGracePeriodEndsAt(now.plus(Duration.ofDays(6)));
		Entitlements e = get();
		assertThat(e.state()).isEqualTo(State.PAST_DUE);
		assertThat(e.paymentOverdue()).isTrue();
		assertThat(e.modules()).containsExactly("crm");

		row.setGracePeriodEndsAt(now.minus(Duration.ofMinutes(1)));
		e = get();
		assertThat(e.state()).isEqualTo(State.RESTRICTED);
		assertThat(e.modules()).isEmpty();
		assertThatThrownBy(() -> service.requireModule(orgId, BillingModule.CRM))
				.isInstanceOf(ApiException.class).hasMessageContaining("payment is overdue");
	}

	@Test
	void missingGraceDateFallsBackToPeriodStartPlusGraceDays() {
		subscription("past_due", "crm");
		row.setCurrentPeriodStart(now.minus(Duration.ofDays(10)));
		assertThat(get().state()).isEqualTo(State.RESTRICTED);
	}

	@Test
	void paidThenCancelledDoesNotGetTheTrialBack() {
		subscription("canceled", "crm");
		row.setPaidBillingStartedAt(now.minus(Duration.ofDays(1)));
		Entitlements e = get();
		assertThat(e.state()).isEqualTo(State.INACTIVE);
		assertThatThrownBy(() -> service.requireModule(orgId, BillingModule.CRM))
				.isInstanceOf(ApiException.class).hasMessageContaining("subscription has ended");
	}

	@Test
	void cancelledDuringTrialWithoutPayingKeepsTheTrial() {
		subscription("canceled", "crm");
		assertThat(get().state()).isEqualTo(State.TRIAL);
	}

	@Test
	void scheduledCancellationKeepsAccessUntilPeriodEnd() {
		subscription("active", "crm");
		row.setPaidBillingStartedAt(now.minus(Duration.ofDays(2)));
		row.setCancelAtPeriodEnd(true);
		Entitlements e = get();
		assertThat(e.state()).isEqualTo(State.ACTIVE);
		assertThat(e.cancellationScheduled()).isTrue();
		assertThat(e.accessEndsAt()).isEqualTo(row.getCurrentPeriodEnd());
	}

	@Test
	void seatLimitBlocksNewMembersWhenFull() {
		lenient().when(access.billableMembers(eq(orgId))).thenReturn(10);
		assertThatThrownBy(() -> service.requireSeatForNewMember(orgId))
				.isInstanceOf(ApiException.class).hasMessageContaining("up to 10 team members");

		subscription("active", "crm");
		item(SubscriptionItem.SEAT_KEY, 12, SubscriptionItemStatus.ACTIVE);
		row.setPaidBillingStartedAt(now);
		service.evict(orgId);
		service.requireSeatForNewMember(orgId); // 10 of 12 used: allowed
	}
}
