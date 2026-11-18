package com.crm.crm_backend.operations.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.crm.domain.LeadStatus;
import com.crm.crm_backend.crm.domain.Lifecycle;
import com.crm.crm_backend.crm.infrastructure.PatientRepository;
import com.crm.crm_backend.operations.api.dto.DashboardSummaryResponse;
import com.crm.crm_backend.operations.api.dto.DashboardSummaryResponse.AttentionItem;
import com.crm.crm_backend.operations.domain.EmploymentStatus;
import com.crm.crm_backend.operations.domain.VendorBaaStatus;
import com.crm.crm_backend.operations.infrastructure.StaffMemberRepository;
import com.crm.crm_backend.operations.infrastructure.VendorRepository;
import com.crm.crm_backend.organization.infrastructure.OrganizationRepository;
import com.crm.crm_backend.shared.security.RoleGuard;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.shared.tenant.TenantContext;

@Service
public class DashboardService {

	private final PatientRepository leadRepository;
	private final StaffMemberRepository staffRepository;
	private final VendorRepository vendorRepository;
	private final OrganizationRepository organizationRepository;

	public DashboardService(
			PatientRepository leadRepository,
			StaffMemberRepository staffRepository,
			VendorRepository vendorRepository,
			OrganizationRepository organizationRepository
	) {
		this.leadRepository = leadRepository;
		this.staffRepository = staffRepository;
		this.vendorRepository = vendorRepository;
		this.organizationRepository = organizationRepository;
	}

	@Transactional(readOnly = true)
	public DashboardSummaryResponse summary(UserPrincipal principal) {
		RoleGuard.requireOrgAccess(principal, "dash");
		var orgId = TenantContext.requireOrganizationId();
		// Only surface alerts for the modules this practice actually turned on.
		Set<String> enabled = organizationRepository.findById(orgId)
				.map(o -> o.getEnabledServices() == null ? Set.<String>of() : Set.copyOf(o.getEnabledServices()))
				.orElse(Set.of());
		boolean crm = enabled.contains("crm");
		boolean ops = enabled.contains("ops");

		long leads = leadRepository.countByOrganizationIdAndLifecycleStageAndLeadStatusIn(
				orgId,
				Lifecycle.LEAD,
				List.of(LeadStatus.NEW, LeadStatus.ATTEMPTING_CONTACT, LeadStatus.CONTACTED, LeadStatus.QUALIFIED,
						LeadStatus.APPOINTMENT_SCHEDULED, LeadStatus.SHOWED)
		);
		long openFollowUps = leadRepository.countByOrganizationIdAndLifecycleStageAndLeadStatusIn(
				orgId,
				Lifecycle.LEAD,
				List.of(LeadStatus.NEW, LeadStatus.ATTEMPTING_CONTACT, LeadStatus.CONTACTED, LeadStatus.QUALIFIED)
		);
		long providers = staffRepository.countByOrganizationIdAndProvider(orgId, true);
		long onboarding = staffRepository.countByOrganizationIdAndStatus(orgId, EmploymentStatus.ONBOARDING);
		long expiring = staffRepository.countExpiringLicenses(orgId, LocalDate.now().plusDays(60));
		long baaGaps = vendorRepository.countByOrganizationIdAndBaaStatusIn(
				orgId,
				List.of(VendorBaaStatus.PENDING, VendorBaaStatus.MISSING)
		);

		List<AttentionItem> attention = new ArrayList<>();
		if (crm && openFollowUps > 0) {
			attention.add(new AttentionItem(
					openFollowUps + " patient lead" + (openFollowUps == 1 ? "" : "s") + " need follow-up",
					"Patient leads & calls",
					"amber",
					"crm"
			));
		}
		if (ops && expiring > 0) {
			attention.add(new AttentionItem(
					expiring + " provider license" + (expiring == 1 ? "" : "s") + " expire within 60 days",
					"Credentials",
					"red",
					"ops"
			));
		}
		if (ops && baaGaps > 0) {
			attention.add(new AttentionItem(
					baaGaps + " vendor BAA" + (baaGaps == 1 ? "" : "s") + " still open",
					"Vendors",
					"amber",
					"ops"
			));
		}
		if (ops && onboarding > 0) {
			attention.add(new AttentionItem(
					onboarding + " staff member" + (onboarding == 1 ? "" : "s") + " still onboarding",
					"Staff",
					"ink",
					"ops"
			));
		}
		if (attention.isEmpty()) {
			attention.add(new AttentionItem(
					"You're caught up",
					"No urgent practice items right now",
					"teal",
					null
			));
		}

		return new DashboardSummaryResponse(
				leads,
				openFollowUps,
				providers,
				onboarding,
				expiring,
				baaGaps,
				attention.stream().limit(4).toList()
		);
	}
}
