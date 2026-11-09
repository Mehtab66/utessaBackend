package com.crm.crm_backend.identity.api.dto;

public record SignupResponse(
		String message,
		String email,
		boolean requiresDemo
) {
}
