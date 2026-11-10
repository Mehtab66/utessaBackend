package com.crm.crm_backend.operations.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.operations.api.dto.DashboardSummaryResponse;
import com.crm.crm_backend.operations.api.dto.DashboardSummaryResponse.AttentionItem;
import com.crm.crm_backend.operations.domain.EmploymentStatus;
import com.crm.crm_backend.operations.domain.LeadStatus;
import com.crm.crm_backend.operations.domain.VendorBaaStatus;
import com.crm.crm_backend.operations.infrastructure.PatientLeadRepository;
import com.crm.crm_backend.operations.infrastructure.StaffMemberRepository;
import com.crm.crm_backend.operations.infrastructure.VendorRepository;
import com.crm.crm_backend.shared.security.RoleGuard;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.shared.tenant.TenantContext;

@Service
public class DashboardService {

	private final PatientLeadRepository leadRepository;
	private final StaffMemberRepository staffRepository;
	private final VendorRepository vendorRepository;

	public DashboardService(
			PatientLeadRepository leadRepository,
			StaffMemberRepository staffRepository,
			VendorRepository vendorRepository
	) {
		this.leadRepository = leadRepository;
		this.staffRepository = staffRepository;
		this.vendorRepository = vendorRepository;
	}

	@Transactional(readOnly = true)
	public DashboardSummaryResponse summary(UserPrincipal principal) {
		RoleGuard.requireOrgAccess(principal, "dash");
		var orgId = TenantContext.requireOrganizationId();

		long leads = leadRepository.countByOrganizationId(orgId);
		long openFollowUps = leadRepository.countByOrganizationIdAndStatusIn(
				orgId,
				List.of(LeadStatus.NEW, LeadStatus.CONTACTED, LeadStatus.SCHEDULED)
		);
		long providers = staffRepository.countByOrganizationIdAndProvider(orgId, true);
		long onboarding = staffRepository.countByOrganizationIdAndStatus(orgId, EmploymentStatus.ONBOARDING);
		long expiring = staffRepository.countExpiringLicenses(orgId, LocalDate.now().plusDays(60));
		long baaGaps = vendorRepository.countByOrganizationIdAndBaaStatusIn(
				orgId,
				List.of(VendorBaaStatus.PENDING, VendorBaaStatus.MISSING)
		);

		List<AttentionItem> attention = new ArrayList<>();
		if (openFollowUps > 0) {
			attention.add(new AttentionItem(
					openFollowUps + " patient lead" + (openFollowUps == 1 ? "" : "s") + " need follow-up",
					"Patient leads & calls",
					"amber"
			));
		}
		if (expiring > 0) {
			attention.add(new AttentionItem(
					expiring + " provider license" + (expiring == 1 ? "" : "s") + " expire within 60 days",
					"Credentials",
					"red"
			));
		}
		if (baaGaps > 0) {
			attention.add(new AttentionItem(
					baaGaps + " vendor BAA" + (baaGaps == 1 ? "" : "s") + " still open",
					"Vendors",
					"amber"
			));
		}
		if (onboarding > 0) {
			attention.add(new AttentionItem(
					onboarding + " staff member" + (onboarding == 1 ? "" : "s") + " still onboarding",
					"Staff",
					"ink"
			));
		}
		if (attention.isEmpty()) {
			attention.add(new AttentionItem(
					"You're caught up",
					"No urgent practice items right now",
					"teal"
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
