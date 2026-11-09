package com.crm.crm_backend.identity.api.dto;

public record AuthResponse(
		String token,
		MeResponse user
) {
}
