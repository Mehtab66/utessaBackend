package com.crm.crm_backend.billing;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.crm.crm_backend.billing.application.BillingTestData;
import com.crm.crm_backend.billing.application.PricingCatalog;
import com.crm.crm_backend.billing.application.StripeGateway;
import com.crm.crm_backend.billing.domain.OrganizationSubscription;
import com.crm.crm_backend.billing.infrastructure.OrganizationSubscriptionRepository;
import com.crm.crm_backend.organization.domain.Organization;
import com.crm.crm_backend.organization.infrastructure.OrganizationRepository;

/**
 * Real Spring context and real PostgreSQL ({@code utessa_crm_test}, migrated by Flyway); only the
 * two classes that talk to Stripe are mocked, so no test ever calls Stripe.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class BillingIntegrationTestBase {

	@MockitoBean
	protected StripeGateway gateway;

	@MockitoBean
	protected PricingCatalog catalog;

	@Autowired
	protected OrganizationRepository organizations;

	@Autowired
	protected OrganizationSubscriptionRepository subscriptions;

	@BeforeEach
	void stubPrices() {
		BillingTestData.stubCatalog(catalog);
	}

	/** A fresh practice, signed up {@code daysAgo} days ago (14-day trial from then). */
	protected Organization practice(int daysAgo) {
		Organization o = new Organization();
		o.setName("IT Practice " + UUID.randomUUID().toString().substring(0, 8));
		o.setSlug("it-" + UUID.randomUUID());
		Instant start = Instant.now().minus(Duration.ofDays(daysAgo));
		o.setTrialStartsAt(start);
		o.setTrialEndsAt(start.plus(Duration.ofDays(14)));
		Set<String> services = new HashSet<>(Set.of("crm", "ops", "mkt", "tc"));
		o.setEnabledServices(services);
		return organizations.saveAndFlush(o);
	}

	/** Links the practice to a Stripe customer + subscription (ids are fake; Stripe is mocked). */
	protected OrganizationSubscription billingRow(UUID orgId, String customerId, String subscriptionId) {
		OrganizationSubscription row = new OrganizationSubscription();
		row.setOrganizationId(orgId);
		row.setStripeCustomerId(customerId);
		row.setStripeSubscriptionId(subscriptionId);
		return subscriptions.saveAndFlush(row);
	}
}
