package com.crm.crm_backend.organization.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.organization.domain.Organization;
import com.crm.crm_backend.organization.infrastructure.OrganizationRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

@RestController
@RequestMapping("/api/v1/org")
public class OrganizationController {

	private final OrganizationRepository organizationRepository;

	public OrganizationController(OrganizationRepository organizationRepository) {
		this.organizationRepository = organizationRepository;
	}

	@GetMapping
	public OrgResponse current(@AuthenticationPrincipal UserPrincipal principal) {
		Organization org = organizationRepository.findById(principal.organizationId())
				.orElseThrow(() -> ApiException.notFound("Organization not found."));
		return new OrgResponse(
				org.getId(),
				org.getName(),
				org.getSlug(),
				org.getTeamSizeBand(),
				org.getOrgType(),
				org.getEhrName(),
				org.getTrialStartsAt(),
				org.getTrialEndsAt(),
				org.isOnboardingDone(),
				org.getEnabledServices()
		);
	}

	public record OrgResponse(
			UUID id,
			String name,
			String slug,
			Integer teamSizeBand,
			String orgType,
			String ehrName,
			Instant trialStartsAt,
			Instant trialEndsAt,
			boolean onboardingDone,
			Set<String> enabledServices
	) {
	}
}
