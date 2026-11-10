package com.crm.crm_backend.platform.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.identity.domain.PlatformRole;
import com.crm.crm_backend.identity.domain.User;
import com.crm.crm_backend.identity.infrastructure.UserRepository;
import com.crm.crm_backend.organization.domain.Membership;
import com.crm.crm_backend.organization.domain.Organization;
import com.crm.crm_backend.organization.infrastructure.MembershipRepository;
import com.crm.crm_backend.organization.infrastructure.OrganizationRepository;
import com.crm.crm_backend.platform.api.dto.PlatformDtos.AttentionItem;
import com.crm.crm_backend.platform.api.dto.PlatformDtos.MonthCount;
import com.crm.crm_backend.platform.api.dto.PlatformDtos.OrganizationRow;
import com.crm.crm_backend.platform.api.dto.PlatformDtos.OverviewResponse;
import com.crm.crm_backend.platform.api.dto.PlatformDtos.PlatformUserRow;
import com.crm.crm_backend.platform.api.dto.PlatformDtos.TypeCount;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

@Service
public class PlatformService {

	private final OrganizationRepository organizationRepository;
	private final MembershipRepository membershipRepository;
	private final UserRepository userRepository;

	public PlatformService(
			OrganizationRepository organizationRepository,
			MembershipRepository membershipRepository,
			UserRepository userRepository
	) {
		this.organizationRepository = organizationRepository;
		this.membershipRepository = membershipRepository;
		this.userRepository = userRepository;
	}

	@Transactional(readOnly = true)
	public OverviewResponse overview(UserPrincipal principal) {
		requireSuperAdmin(principal);
		List<Organization> orgs = organizationRepository.findAll();
		List<Membership> memberships = membershipRepository.findAll();
		List<User> users = userRepository.findAll();

		Instant now = Instant.now();
		Instant soon = now.plusSeconds(7L * 24 * 3600);

		long trials = orgs.stream().filter(o -> o.getTrialEndsAt() != null && o.getTrialEndsAt().isAfter(now)).count();
		long endingSoon = orgs.stream()
				.filter(o -> o.getTrialEndsAt() != null && o.getTrialEndsAt().isAfter(now) && !o.getTrialEndsAt().isAfter(soon))
				.count();
		long verified = users.stream().filter(User::isEmailVerified).count();
		long supers = users.stream().filter(User::isSuperAdmin).count();
		long incomplete = orgs.stream().filter(o -> !o.isOnboardingDone()).count();

		Map<String, Long> byType = orgs.stream()
				.collect(Collectors.groupingBy(
						o -> blank(o.getOrgType(), "Unspecified"),
						LinkedHashMap::new,
						Collectors.counting()
				));

		List<TypeCount> typeCounts = byType.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.map(e -> new TypeCount(e.getKey(), e.getValue()))
				.toList();

		List<MonthCount> enrolled = enrolledByMonth(orgs);
		List<AttentionItem> attention = buildAttention(orgs, users, now, soon);

		return new OverviewResponse(
				orgs.size(),
				trials,
				endingSoon,
				memberships.size(),
				verified,
				supers,
				incomplete,
				typeCounts,
				enrolled,
				attention,
				now
		);
	}

	@Transactional(readOnly = true)
	public List<OrganizationRow> organizations(UserPrincipal principal) {
		requireSuperAdmin(principal);
		Map<UUID, Long> memberCounts = membershipRepository.findAll().stream()
				.collect(Collectors.groupingBy(Membership::getOrganizationId, Collectors.counting()));
		Instant now = Instant.now();
		return organizationRepository.findAll().stream()
				.sorted(Comparator.comparing(Organization::getCreatedAt).reversed())
				.map(o -> new OrganizationRow(
						o.getId(),
						o.getName(),
						o.getSlug(),
						blank(o.getOrgType(), "—"),
						o.getTeamSizeBand(),
						o.isOnboardingDone(),
						o.getTrialStartsAt(),
						o.getTrialEndsAt(),
						statusFor(o, now),
						memberCounts.getOrDefault(o.getId(), 0L),
						o.getCreatedAt()
				))
				.toList();
	}

	@Transactional(readOnly = true)
	public List<PlatformUserRow> platformUsers(UserPrincipal principal) {
		requireSuperAdmin(principal);
		return userRepository.findAll().stream()
				.filter(User::isSuperAdmin)
				.sorted(Comparator.comparing(User::getCreatedAt))
				.map(u -> new PlatformUserRow(
						u.getId(),
						u.getFullName(),
						u.getEmail(),
						u.isEmailVerified(),
						u.getPlatformRole() == null ? "NONE" : u.getPlatformRole().name(),
						u.getCreatedAt()
				))
				.toList();
	}

	private static void requireSuperAdmin(UserPrincipal principal) {
		if (principal == null || !principal.superAdmin()) {
			throw ApiException.forbidden("Platform console is for super admins only.");
		}
	}

	private static String statusFor(Organization o, Instant now) {
		if (o.getTrialEndsAt() != null && o.getTrialEndsAt().isAfter(now)) {
			return "Trial";
		}
		if (!o.isOnboardingDone()) {
			return "Onboarding";
		}
		return "Active";
	}

	private static List<MonthCount> enrolledByMonth(List<Organization> orgs) {
		LocalDate end = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
		List<LocalDate> months = new ArrayList<>();
		for (int i = 4; i >= 0; i--) {
			months.add(end.minusMonths(i));
		}
		List<MonthCount> out = new ArrayList<>();
		for (LocalDate monthStart : months) {
			LocalDate next = monthStart.plusMonths(1);
			Instant cutoff = next.atStartOfDay().toInstant(ZoneOffset.UTC);
			long count = orgs.stream()
					.filter(o -> o.getCreatedAt() != null && o.getCreatedAt().isBefore(cutoff))
					.count();
			String label = monthStart.getMonth().getDisplayName(TextStyle.SHORT, Locale.US);
			out.add(new MonthCount(label, count));
		}
		return out;
	}

	private static List<AttentionItem> buildAttention(
			List<Organization> orgs,
			List<User> users,
			Instant now,
			Instant soon
	) {
		List<AttentionItem> items = new ArrayList<>();
		for (Organization o : orgs) {
			if (!o.isOnboardingDone()) {
				items.add(new AttentionItem(
						"Onboarding",
						o.getName() + " has not finished practice setup",
						"Owner still on the welcome flow",
						"/platform/organizations"
				));
			}
			if (o.getTrialEndsAt() != null && o.getTrialEndsAt().isAfter(now) && !o.getTrialEndsAt().isAfter(soon)) {
				items.add(new AttentionItem(
						"Trial",
						o.getName() + ": trial ending soon",
						"Trial ends " + o.getTrialEndsAt(),
						"/platform/organizations"
				));
			}
		}
		long unverified = users.stream().filter(u -> !u.isEmailVerified() && !u.isSuperAdmin()).count();
		if (unverified > 0) {
			items.add(new AttentionItem(
					"Verify",
					unverified + " user" + (unverified == 1 ? "" : "s") + " still need email verification",
					"Practice accounts awaiting code confirmation",
					"/platform/access"
			));
		}
		return items.stream().limit(12).toList();
	}

	private static String blank(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}
}
