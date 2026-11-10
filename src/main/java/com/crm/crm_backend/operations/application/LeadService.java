package com.crm.crm_backend.operations.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.operations.api.dto.LeadDtos.LeadRequest;
import com.crm.crm_backend.operations.api.dto.LeadDtos.LeadResponse;
import com.crm.crm_backend.operations.domain.LeadStatus;
import com.crm.crm_backend.operations.domain.PatientLead;
import com.crm.crm_backend.operations.infrastructure.PatientLeadRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.RoleGuard;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.shared.tenant.TenantContext;

@Service
public class LeadService {

	private final PatientLeadRepository repository;

	public LeadService(PatientLeadRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public List<LeadResponse> list(UserPrincipal principal) {
		RoleGuard.requireOrgAccess(principal, "patients");
		return repository.findByOrganizationIdOrderByCreatedAtDesc(TenantContext.requireOrganizationId())
				.stream()
				.map(this::toResponse)
				.toList();
	}

	@Transactional
	public LeadResponse create(UserPrincipal principal, LeadRequest request) {
		RoleGuard.requireOrgAccess(principal, "patients");
		PatientLead lead = new PatientLead();
		apply(lead, request);
		return toResponse(repository.save(lead));
	}

	@Transactional
	public LeadResponse update(UserPrincipal principal, UUID id, LeadRequest request) {
		RoleGuard.requireOrgAccess(principal, "patients");
		PatientLead lead = require(id);
		apply(lead, request);
		return toResponse(repository.save(lead));
	}

	@Transactional
	public void delete(UserPrincipal principal, UUID id) {
		RoleGuard.requireOrgAccess(principal, "patients");
		repository.delete(require(id));
	}

	private PatientLead require(UUID id) {
		return repository.findByIdAndOrganizationId(id, TenantContext.requireOrganizationId())
				.orElseThrow(() -> ApiException.notFound("Lead not found."));
	}

	private void apply(PatientLead lead, LeadRequest request) {
		lead.setFullName(request.fullName().trim());
		lead.setPhone(blankToNull(request.phone()));
		lead.setEmail(blankToNull(request.email()));
		lead.setSource(blankToNull(request.source()));
		lead.setPreferredContact(blankToNull(request.preferredContact()));
		lead.setReferralSourceDetail(blankToNull(request.referralSourceDetail()));
		lead.setInterestedService(blankToNull(request.interestedService()));
		lead.setPreferredProvider(blankToNull(request.preferredProvider()));
		lead.setInsurancePayer(blankToNull(request.insurancePayer()));
		lead.setStatus(request.status() == null ? LeadStatus.NEW : request.status());
		lead.setOwnerName(blankToNull(request.ownerName()));
		lead.setNextFollowUp(request.nextFollowUp());
		lead.setLastContactAt(request.lastContactAt());
		lead.setCallOutcome(blankToNull(request.callOutcome()));
		lead.setNotes(blankToNull(request.notes()));
	}

	private LeadResponse toResponse(PatientLead lead) {
		return new LeadResponse(
				lead.getId(), lead.getFullName(), lead.getPhone(), lead.getEmail(), lead.getSource(),
				lead.getPreferredContact(), lead.getReferralSourceDetail(), lead.getInterestedService(),
				lead.getPreferredProvider(), lead.getInsurancePayer(), lead.getStatus(), lead.getOwnerName(),
				lead.getNextFollowUp(), lead.getLastContactAt(), lead.getCallOutcome(), lead.getNotes(),
				lead.getCreatedAt(), lead.getUpdatedAt()
		);
	}

	private String blankToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}
}
