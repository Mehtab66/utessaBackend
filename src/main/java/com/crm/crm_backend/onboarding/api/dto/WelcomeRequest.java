package com.crm.crm_backend.onboarding.api.dto;

import java.util.Set;

public record WelcomeRequest(
		String orgType,
		String ehrName,
		Set<String> enabledServices
) {
}
