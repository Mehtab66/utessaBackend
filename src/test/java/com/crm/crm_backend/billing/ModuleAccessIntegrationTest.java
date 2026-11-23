package com.crm.crm_backend.billing;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.crm.crm_backend.billing.domain.BillingInterval;
import com.crm.crm_backend.billing.domain.OrganizationSubscription;
import com.crm.crm_backend.billing.domain.SubscriptionItem;
import com.crm.crm_backend.billing.infrastructure.SubscriptionItemRepository;
import com.crm.crm_backend.shared.security.JwtService;
import com.crm.crm_backend.shared.security.SessionCookieService;

import jakarta.servlet.http.Cookie;

/** The backend refuses module APIs the practice isn't entitled to — whatever the frontend shows. */
@AutoConfigureMockMvc
class ModuleAccessIntegrationTest extends BillingIntegrationTestBase {

	@Autowired
	MockMvc mvc;
	@Autowired
	JwtService jwt;
	@Autowired
	SubscriptionItemRepository items;

	private Cookie sessionFor(UUID orgId) {
		String token = jwt.generateToken(UUID.randomUUID(), orgId, "owner@example.test", "OWNER", true, false);
		return new Cookie(SessionCookieService.COOKIE_NAME, token);
	}

	@Test
	void trialPracticeCanUseEveryModule() throws Exception {
		UUID org = practice(1).getId();
		mvc.perform(get("/api/v1/timeclock/health").cookie(sessionFor(org))).andExpect(status().isOk());
		mvc.perform(get("/api/v1/billing/entitlements").cookie(sessionFor(org)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("TRIAL"))
				.andExpect(jsonPath("$.maxMembers").value(10));
	}

	@Test
	void paidPracticeIsLimitedToItsPlanButCanAlwaysReachBilling() throws Exception {
		UUID org = practice(1).getId();
		OrganizationSubscription row = billingRow(org, "cus_gate_" + UUID.randomUUID(), "sub_gate_" + UUID.randomUUID());
		row.setSubscriptionStatus("active");
		row.setBillingInterval(BillingInterval.MONTHLY);
		row.setPaidBillingStartedAt(Instant.now());
		subscriptions.saveAndFlush(row);
		SubscriptionItem crm = new SubscriptionItem();
		crm.setSubscriptionId(row.getId());
		crm.setModuleKey("crm");
		crm.setStripeSubscriptionItemId("si_gate_" + UUID.randomUUID());
		crm.setStripePriceId("price_crm_m");
		crm.setQuantity(1);
		crm.setBillingInterval(BillingInterval.MONTHLY);
		crm.setUnitAmount(3000);
		crm.setCurrency("usd");
		items.saveAndFlush(crm);

		Cookie session = sessionFor(org);
		mvc.perform(get("/api/v1/timeclock/health").cookie(session))
				.andExpect(status().isPaymentRequired())
				.andExpect(jsonPath("$.message").value(containsString("isn't part of your plan")));
		mvc.perform(get("/api/v1/marketing/health").cookie(session)).andExpect(status().isPaymentRequired());
		mvc.perform(get("/api/v1/crm/health").cookie(session)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/billing/entitlements").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("ACTIVE"))
				.andExpect(jsonPath("$.modules[0]").value("crm"));
	}
}
