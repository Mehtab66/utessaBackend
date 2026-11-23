package com.crm.crm_backend.billing.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the module entitlement gate for all API routes. */
@Configuration
public class BillingWebConfig implements WebMvcConfigurer {

	private final ModuleAccessInterceptor moduleAccess;

	public BillingWebConfig(ModuleAccessInterceptor moduleAccess) {
		this.moduleAccess = moduleAccess;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(moduleAccess).addPathPatterns("/api/v1/**");
	}
}
