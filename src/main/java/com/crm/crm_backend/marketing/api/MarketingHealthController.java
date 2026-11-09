package com.crm.crm_backend.marketing.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.shared.tenant.TenantContext;

@RestController
@RequestMapping("/api/v1/marketing")
public class MarketingHealthController {

	@GetMapping("/health")
	public Map<String, Object> health() {
		return Map.of(
				"module", "marketing",
				"status", "ready",
				"organizationId", TenantContext.requireOrganizationId().toString()
		);
	}
}
