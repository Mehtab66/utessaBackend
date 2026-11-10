package com.crm.crm_backend.operations.api.dto;

import java.util.List;

public record DashboardSummaryResponse(
		long activePatientLeads,
		long openLeadFollowUps,
		long providerCount,
		long staffOnboarding,
		long expiringLicenses,
		long vendorsNeedingBaa,
		List<AttentionItem> attention
) {
	public record AttentionItem(String title, String meta, String tone) {
	}
}
