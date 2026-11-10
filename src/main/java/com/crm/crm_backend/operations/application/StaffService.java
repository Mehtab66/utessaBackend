package com.crm.crm_backend.operations.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.operations.api.dto.StaffDtos.StaffRequest;
import com.crm.crm_backend.operations.api.dto.StaffDtos.StaffResponse;
import com.crm.crm_backend.operations.domain.EmploymentStatus;
import com.crm.crm_backend.operations.domain.StaffMember;
import com.crm.crm_backend.operations.infrastructure.StaffMemberRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.RoleGuard;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.shared.tenant.TenantContext;

@Service
public class StaffService {

	private final StaffMemberRepository repository;

	public StaffService(StaffMemberRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public List<StaffResponse> list(UserPrincipal principal, boolean providersOnly) {
		RoleGuard.requireOrgAccess(principal, providersOnly ? "creds" : "staff");
		UUID orgId = TenantContext.requireOrganizationId();
		List<StaffMember> rows = repository.findByOrganizationIdAndProviderOrderByLastNameAscFirstNameAsc(
				orgId, providersOnly);
		return rows.stream().map(this::toResponse).toList();
	}

	@Transactional
	public StaffResponse create(UserPrincipal principal, StaffRequest request, boolean forceProvider) {
		RoleGuard.requireOrgAccess(principal, forceProvider ? "creds" : "staff");
		StaffMember member = new StaffMember();
		apply(member, request, forceProvider);
		return toResponse(repository.save(member));
	}

	@Transactional
	public StaffResponse update(UserPrincipal principal, UUID id, StaffRequest request, boolean forceProvider) {
		StaffMember member = require(id, forceProvider);
		RoleGuard.requireOrgAccess(principal, forceProvider ? "creds" : "staff");
		apply(member, request, forceProvider);
		return toResponse(repository.save(member));
	}

	@Transactional
	public void delete(UserPrincipal principal, UUID id, boolean forceProvider) {
		StaffMember member = require(id, forceProvider);
		RoleGuard.requireOrgAccess(principal, forceProvider ? "creds" : "staff");
		repository.delete(member);
	}

	private StaffMember require(UUID id, boolean provider) {
		StaffMember member = repository.findByIdAndOrganizationId(id, TenantContext.requireOrganizationId())
				.orElseThrow(() -> ApiException.notFound(provider ? "Provider not found." : "Staff member not found."));
		if (member.isProvider() != provider) {
			throw ApiException.notFound(provider ? "Provider not found." : "Staff member not found.");
		}
		return member;
	}

	private void apply(StaffMember m, StaffRequest r, boolean forceProvider) {
		m.setFirstName(r.firstName().trim());
		m.setMiddleName(blankToNull(r.middleName()));
		m.setLastName(r.lastName().trim());
		m.setPreferredName(blankToNull(r.preferredName()));
		m.setDateOfBirth(r.dateOfBirth());
		m.setLanguages(blankToNull(r.languages()));
		m.setStreet(blankToNull(r.street()));
		m.setApt(blankToNull(r.apt()));
		m.setCity(blankToNull(r.city()));
		m.setState(blankToNull(r.state()));
		m.setZip(blankToNull(r.zip()));
		m.setHomePhone(blankToNull(r.homePhone()));
		m.setMobilePhone(blankToNull(r.mobilePhone()));
		m.setWorkPhone(blankToNull(r.workPhone()));
		m.setPhone(blankToNull(r.mobilePhone() != null ? r.mobilePhone() : r.workPhone()));
		m.setPersonalEmail(blankToNull(r.personalEmail()));
		m.setEmail(blankToNull(r.email()));
		m.setEmergencyName(blankToNull(r.emergencyName()));
		m.setEmergencyRelationship(blankToNull(r.emergencyRelationship()));
		m.setEmergencyPhone(blankToNull(r.emergencyPhone()));
		m.setJobTitle(r.jobTitle().trim());
		m.setDepartment(blankToNull(r.department()));
		m.setDesignation(blankToNull(r.designation()));
		m.setManagerName(blankToNull(r.managerName()));
		m.setCategory(r.category().trim());
		m.setProvider(forceProvider);
		m.setStatus(r.status() == null ? EmploymentStatus.ONBOARDING : r.status());
		m.setEmploymentType(blankToNull(r.employmentType()));
		m.setFte(blankToNull(r.fte()));
		m.setHoursPerWeek(blankToNull(r.hoursPerWeek()));
		m.setShift(blankToNull(r.shift()));
		m.setWorkArrangement(blankToNull(r.workArrangement()));
		m.setPayType(blankToNull(r.payType()));
		m.setPayRate(blankToNull(r.payRate()));
		m.setPayFrequency(blankToNull(r.payFrequency()));
		m.setEvalPlan(blankToNull(r.evalPlan()));
		m.setAccessRole(blankToNull(r.accessRole()));
		m.setCreateLogin(Boolean.TRUE.equals(r.createLogin()));
		m.setSpecialty(blankToNull(r.specialty()));
		m.setSubspecialty(blankToNull(r.subspecialty()));
		m.setBoardStatus(blankToNull(r.boardStatus()));
		m.setBoardName(blankToNull(r.boardName()));
		m.setBoardExpiresOn(r.boardExpiresOn());
		m.setPopulationFocus(blankToNull(r.populationFocus()));
		m.setSupervisorName(blankToNull(r.supervisorName()));
		m.setNpi(blankToNull(r.npi()));
		m.setDeaNumber(blankToNull(r.deaNumber()));
		m.setDeaExpiresOn(r.deaExpiresOn());
		m.setTaxonomyCode(blankToNull(r.taxonomyCode()));
		m.setCaqhId(blankToNull(r.caqhId()));
		m.setLicenseType(blankToNull(r.licenseType()));
		m.setLicenseNumber(blankToNull(r.licenseNumber()));
		m.setLicenseExpiresOn(r.licenseExpiresOn());
		m.setLocation(blankToNull(r.location()));
		m.setStartDate(r.startDate());
		m.setTermDate(r.termDate());
		m.setLastDayWorked(r.lastDayWorked());
		m.setSeparationType(blankToNull(r.separationType()));
		m.setRehireEligibility(blankToNull(r.rehireEligibility()));
		m.setSeparationNotes(blankToNull(r.separationNotes()));
		m.setNotes(blankToNull(r.notes()));
		m.setCredentialsJson(r.credentials() == null ? new ArrayList<>() : new ArrayList<>(r.credentials()));
		if (m.isProvider() && (m.getCategory() == null || m.getCategory().isBlank())) {
			m.setCategory("Provider");
		}
	}

	private StaffResponse toResponse(StaffMember m) {
		return new StaffResponse(
				m.getId(), m.getFirstName(), m.getMiddleName(), m.getLastName(), m.getPreferredName(),
				m.getDateOfBirth(), m.getLanguages(), m.getStreet(), m.getApt(), m.getCity(), m.getState(), m.getZip(),
				m.getHomePhone(), m.getMobilePhone(), m.getWorkPhone(), m.getPersonalEmail(), m.getEmail(),
				m.getEmergencyName(), m.getEmergencyRelationship(), m.getEmergencyPhone(),
				m.getJobTitle(), m.getDepartment(), m.getDesignation(), m.getManagerName(), m.getCategory(),
				m.isProvider(), m.getStatus(), m.getEmploymentType(), m.getFte(), m.getHoursPerWeek(), m.getShift(),
				m.getWorkArrangement(), m.getPayType(), m.getPayRate(), m.getPayFrequency(), m.getEvalPlan(),
				m.getAccessRole(), m.isCreateLogin(), m.getSpecialty(), m.getSubspecialty(), m.getBoardStatus(),
				m.getBoardName(), m.getBoardExpiresOn(), m.getPopulationFocus(), m.getSupervisorName(),
				m.getNpi(), m.getDeaNumber(), m.getDeaExpiresOn(), m.getTaxonomyCode(), m.getCaqhId(),
				m.getLicenseType(), m.getLicenseNumber(), m.getLicenseExpiresOn(), m.getLocation(), m.getStartDate(),
				m.getTermDate(), m.getLastDayWorked(), m.getSeparationType(), m.getRehireEligibility(),
				m.getSeparationNotes(), m.getNotes(),
				m.getCredentialsJson() == null ? List.of() : m.getCredentialsJson(),
				m.getCreatedAt(), m.getUpdatedAt()
		);
	}

	private String blankToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}
}
