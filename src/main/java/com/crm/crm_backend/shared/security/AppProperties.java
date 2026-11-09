package com.crm.crm_backend.shared.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
		JwtProperties jwt,
		TrialProperties trial,
		CorsProperties cors
) {
	public record JwtProperties(String secret, long expirationMs) {
	}

	public record TrialProperties(int days) {
	}

	public record CorsProperties(List<String> allowedOrigins) {
	}
}
