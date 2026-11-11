package com.crm.crm_backend.platform.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PlatformDtos {

	private PlatformDtos() {
	}

	public record OverviewResponse(
			long organizationCount,
			long trialCount,
			long activeTrialEndingSoon,
			long memberCount,
			long verifiedUserCount,
			long superAdminCount,
			long onboardingIncomplete,
			List<TypeCount> byOrgType,
			List<MonthCount> enrolledByMonth,
			List<AttentionItem> attention,
			Instant generatedAt
	) {
	}

	public record TypeCount(String type, long count) {
	}

	public record MonthCount(String month, long count) {
	}

	public record AttentionItem(String priority, String title, String detail, String href) {
	}

	public record OrganizationRow(
			UUID id,
			String name,
			String slug,
			String orgType,
			Integer teamSizeBand,
			boolean onboardingDone,
			Instant trialStartsAt,
			Instant trialEndsAt,
			String status,
			long memberCount,
			Instant createdAt
	) {
	}

	public record OrganizationDetail(
			UUID id,
			String name,
			String slug,
			String orgType,
			Integer teamSizeBand,
			String ehrName,
			boolean termsAccepted,
			boolean newsOptIn,
			boolean onboardingDone,
			Instant trialStartsAt,
			Instant trialEndsAt,
			String status,
			List<String> enabledServices,
			long memberCount,
			List<MemberRow> members,
			Instant createdAt,
			Instant updatedAt
	) {
	}

	public record MemberRow(
			UUID id,
			UUID userId,
			String fullName,
			String email,
			String phone,
			boolean emailVerified,
			String role,
			Instant joinedAt
	) {
	}

	public record PlatformUserRow(
			UUID id,
			String fullName,
			String email,
			boolean emailVerified,
			String platformRole,
			Instant createdAt
	) {
	}
}
