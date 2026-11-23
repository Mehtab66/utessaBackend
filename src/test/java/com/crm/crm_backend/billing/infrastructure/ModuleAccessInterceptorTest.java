package com.crm.crm_backend.billing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.crm.crm_backend.billing.domain.BillingModule;

class ModuleAccessInterceptorTest {

	@Test
	void moduleRoutesMapToTheirModule() {
		assertThat(ModuleAccessInterceptor.moduleFor("/api/v1/crm/patients")).isEqualTo(BillingModule.CRM);
		assertThat(ModuleAccessInterceptor.moduleFor("/api/v1/staff")).isEqualTo(BillingModule.OPERATIONS);
		assertThat(ModuleAccessInterceptor.moduleFor("/api/v1/vendors/123")).isEqualTo(BillingModule.OPERATIONS);
		assertThat(ModuleAccessInterceptor.moduleFor("/api/v1/providers")).isEqualTo(BillingModule.OPERATIONS);
		assertThat(ModuleAccessInterceptor.moduleFor("/api/v1/marketing/health")).isEqualTo(BillingModule.MARKETING);
		assertThat(ModuleAccessInterceptor.moduleFor("/api/v1/timeclock/health")).isEqualTo(BillingModule.TIME_CLOCK);
	}

	@Test
	void alwaysOnRoutesAreNotGated() {
		for (String path : new String[] { "/api/v1/billing/subscription", "/api/v1/auth/me", "/api/v1/workspace/tasks",
				"/api/v1/org", "/api/v1/dashboard/summary", "/api/v1/stripe/webhook", "/api/v1/onboarding/welcome" }) {
			assertThat(ModuleAccessInterceptor.moduleFor(path)).as(path).isNull();
		}
	}

	@Test
	void similarPrefixesDoNotMatch() {
		assertThat(ModuleAccessInterceptor.moduleFor("/api/v1/staffing")).isNull();
		assertThat(ModuleAccessInterceptor.moduleFor("/api/v1/crmx")).isNull();
	}
}
