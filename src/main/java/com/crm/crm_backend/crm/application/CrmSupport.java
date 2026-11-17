package com.crm.crm_backend.crm.application;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.crm.crm_backend.crm.api.dto.CrmOverviewDtos.TeamMember;
import com.crm.crm_backend.crm.domain.Activity;
import com.crm.crm_backend.crm.domain.ActivityType;
import com.crm.crm_backend.crm.domain.Patient;
import com.crm.crm_backend.crm.domain.ReferralPartner;
import com.crm.crm_backend.crm.infrastructure.ActivityRepository;
import com.crm.crm_backend.crm.infrastructure.PatientRepository;
import com.crm.crm_backend.crm.infrastructure.ReferralPartnerRepository;
import com.crm.crm_backend.identity.domain.User;
import com.crm.crm_backend.identity.infrastructure.UserRepository;
import com.crm.crm_backend.organization.domain.Membership;
import com.crm.crm_backend.organization.infrastructure.MembershipRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.RoleGuard;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.shared.tenant.TenantContext;

/**
 * Cross-cutting helpers shared by CRM services: access checks, tenant id,
 * team-member resolution, batch lookups and timeline logging.
 */
@Component
public class CrmSupport {

	/** Permission key for day-to-day CRM work (front desk, clinical, providers, managers). */
	public static final String PERM_CRM = "patients";
	/** Permission key for CRM administration (targets, integrations, deletes). */
	public static final String PERM_CRM_ADMIN = "crmadmin";

	private final UserRepository userRepository;
	private final MembershipRepository membershipRepository;
	private final ActivityRepository activityRepository;
	private final PatientRepository patientRepository;
	private final ReferralPartnerRepository partnerRepository;

	public CrmSupport(
			UserRepository userRepository,
			MembershipRepository membershipRepository,
			ActivityRepository activityRepository,
			PatientRepository patientRepository,
			ReferralPartnerRepository partnerRepository
	) {
		this.userRepository = userRepository;
		this.membershipRepository = membershipRepository;
		this.activityRepository = activityRepository;
		this.patientRepository = patientRepository;
		this.partnerRepository = partnerRepository;
	}

	public void requireAccess(UserPrincipal principal) {
		RoleGuard.requireOrgAccess(principal, PERM_CRM);
	}

	public void requireAdmin(UserPrincipal principal) {
		RoleGuard.requireOrgAccess(principal, PERM_CRM_ADMIN);
	}

	public UUID orgId() {
		return TenantContext.requireOrganizationId();
	}

	public String actorName(UserPrincipal principal) {
		if (principal == null) {
			return "System";
		}
		return userRepository.findById(principal.userId())
				.map(User::getFullName)
				.orElse(principal.email());
	}

	/** Resolves a team member's display name, rejecting users outside this practice. */
	public String memberName(UUID userId) {
		if (userId == null) {
			return null;
		}
		membershipRepository.findByUserIdAndOrganizationId(userId, orgId())
				.orElseThrow(() -> ApiException.badRequest("Assigned user is not a member of this practice."));
		return userRepository.findById(userId).map(User::getFullName).orElse(null);
	}

	/** Like {@link #memberName} but returns null instead of failing (used by automation). */
	public String memberNameOrNull(UUID userId) {
		if (userId == null || membershipRepository.findByUserIdAndOrganizationId(userId, orgId()).isEmpty()) {
			return null;
		}
		return userRepository.findById(userId).map(User::getFullName).orElse(null);
	}

	public List<TeamMember> team() {
		List<Membership> members = membershipRepository.findByOrganizationId(orgId());
		Map<UUID, User> users = userRepository.findAllById(members.stream().map(Membership::getUserId).toList())
				.stream()
				.collect(Collectors.toMap(User::getId, Function.identity()));
		return members.stream()
				.filter(m -> users.containsKey(m.getUserId()))
				.map(m -> {
					User u = users.get(m.getUserId());
					return new TeamMember(u.getId(), u.getFullName(), u.getEmail(), m.getRole().name());
				})
				.sorted(Comparator.comparing(TeamMember::fullName, String.CASE_INSENSITIVE_ORDER))
				.toList();
	}

	public Map<UUID, Patient> patients(Collection<UUID> ids) {
		Set<UUID> clean = ids.stream().filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
		if (clean.isEmpty()) {
			return Map.of();
		}
		return patientRepository.findByOrganizationIdAndIdIn(orgId(), clean).stream()
				.collect(Collectors.toMap(Patient::getId, Function.identity()));
	}

	public Patient requirePatient(UUID id) {
		if (id == null) {
			throw ApiException.badRequest("Select a patient or lead.");
		}
		return patientRepository.findByIdAndOrganizationId(id, orgId())
				.orElseThrow(() -> ApiException.notFound("Patient or lead not found."));
	}

	public Map<UUID, ReferralPartner> partners() {
		return partnerRepository.findByOrganizationIdOrderByNameAsc(orgId()).stream()
				.collect(Collectors.toMap(ReferralPartner::getId, Function.identity()));
	}

	public ReferralPartner requirePartner(UUID id) {
		return partnerRepository.findByIdAndOrganizationId(id, orgId())
				.orElseThrow(() -> ApiException.notFound("Referral partner not found."));
	}

	public Activity log(
			UserPrincipal principal,
			ActivityType type,
			UUID patientId,
			UUID opportunityId,
			UUID referralId,
			String subject,
			String body
	) {
		Activity activity = new Activity();
		activity.setActivityType(type);
		activity.setPatientId(patientId);
		activity.setOpportunityId(opportunityId);
		activity.setReferralId(referralId);
		activity.setSubject(truncate(subject, 255));
		activity.setBody(clean(body));
		if (principal != null) {
			activity.setActorUserId(principal.userId());
		}
		activity.setActorName(actorName(principal));
		return activityRepository.save(activity);
	}

	public Activity save(Activity activity) {
		return activityRepository.save(activity);
	}

	// ── text helpers ────────────────────────────────────────────────────

	public static String clean(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}

	public static String truncate(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max);
	}

	/** Keeps the last 10 digits so "+1 (555) 010-2000" and "5550102000" match. */
	public static String normalizePhone(String phone) {
		if (phone == null) {
			return null;
		}
		String digits = phone.replaceAll("[^0-9]", "");
		if (digits.isEmpty()) {
			return null;
		}
		return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
	}

	public static String humanize(Enum<?> value) {
		if (value == null) {
			return "";
		}
		String lower = value.name().replace('_', ' ').toLowerCase();
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	public static String joinName(String first, String last) {
		String f = clean(first);
		String l = clean(last);
		if (f == null) {
			return l;
		}
		return l == null ? f : f + " " + l;
	}
}
