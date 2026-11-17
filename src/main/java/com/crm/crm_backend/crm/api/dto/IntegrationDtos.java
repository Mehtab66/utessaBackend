package com.crm.crm_backend.crm.api.dto;

import java.time.Instant;
import java.util.Map;

import jakarta.validation.constraints.Size;

public final class IntegrationDtos {

	private IntegrationDtos() {
	}

	public record IntegrationRequest(
			@Size(max = 100) String provider,
			@Size(max = 30) String status,
			Map<String, Object> settings
	) {
	}

	public record IntegrationResponse(
			String kind,
			String provider,
			String status,
			Map<String, Object> settings,
			Instant lastSyncAt,
			long pendingPatients,
			long pendingAppointments,
			long linkedPatients,
			Instant updatedAt
	) {
	}
}
