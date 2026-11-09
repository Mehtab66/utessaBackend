package com.crm.crm_backend.identity.api.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record MeResponse(
		UUID userId,
		String email,
		String fullName,
		boolean emailVerified,
		UUID organizationId,
		String organizationName,
		String role,
		Instant trialStartsAt,
		Instant trialEndsAt,
		boolean onboardingDone,
		Set<String> enabledServices
) {
}
