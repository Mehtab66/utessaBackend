package com.crm.crm_backend.operations.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.operations.api.dto.VendorDtos.VendorRequest;
import com.crm.crm_backend.operations.api.dto.VendorDtos.VendorResponse;
import com.crm.crm_backend.operations.domain.Vendor;
import com.crm.crm_backend.operations.domain.VendorBaaStatus;
import com.crm.crm_backend.operations.infrastructure.VendorRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.RoleGuard;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.shared.tenant.TenantContext;

@Service
public class VendorService {

	private final VendorRepository repository;

	public VendorService(VendorRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public List<VendorResponse> list(UserPrincipal principal) {
		RoleGuard.requireOrgAccess(principal, "vendors");
		return repository.findByOrganizationIdOrderByNameAsc(TenantContext.requireOrganizationId())
				.stream()
				.map(this::toResponse)
				.toList();
	}

	@Transactional
	public VendorResponse create(UserPrincipal principal, VendorRequest request) {
		RoleGuard.requireOrgAccess(principal, "vendors");
		Vendor vendor = new Vendor();
		apply(vendor, request);
		return toResponse(repository.save(vendor));
	}

	@Transactional
	public VendorResponse update(UserPrincipal principal, UUID id, VendorRequest request) {
		RoleGuard.requireOrgAccess(principal, "vendors");
		Vendor vendor = require(id);
		apply(vendor, request);
		return toResponse(repository.save(vendor));
	}

	@Transactional
	public void delete(UserPrincipal principal, UUID id) {
		RoleGuard.requireOrgAccess(principal, "vendors");
		repository.delete(require(id));
	}

	private Vendor require(UUID id) {
		return repository.findByIdAndOrganizationId(id, TenantContext.requireOrganizationId())
				.orElseThrow(() -> ApiException.notFound("Vendor not found."));
	}

	private void apply(Vendor v, VendorRequest r) {
		v.setName(r.name().trim());
		v.setCategory(r.category().trim());
		v.setFolder(blankToNull(r.folder() != null ? r.folder() : r.category()));
		v.setContactName(blankToNull(r.contactName()));
		v.setContactRole(blankToNull(r.contactRole()));
		v.setEmail(blankToNull(r.email()));
		v.setPhone(blankToNull(r.phone()));
		v.setWebsite(blankToNull(r.website()));
		v.setAccountNumber(blankToNull(r.accountNumber()));
		v.setHandlesPhi(Boolean.TRUE.equals(r.handlesPhi()));
		v.setContractStart(r.contractStart());
		v.setContractEnd(r.contractEnd());
		v.setAutoRenews(Boolean.TRUE.equals(r.autoRenews()));
		v.setCancellationNoticeDays(r.cancellationNoticeDays());
		v.setMonthlyCost(r.monthlyCost());
		v.setPaymentTerms(blankToNull(r.paymentTerms()));
		v.setBaaStatus(r.baaStatus() == null ? VendorBaaStatus.PENDING : r.baaStatus());
		v.setBaaSignedOn(r.baaSignedOn());
		v.setNotes(blankToNull(r.notes()));
		v.setActive(r.active() == null || r.active());
		v.setDocumentsJson(r.documents() == null ? new ArrayList<>() : new ArrayList<>(r.documents()));
	}

	private VendorResponse toResponse(Vendor v) {
		return new VendorResponse(
				v.getId(), v.getName(), v.getCategory(), v.getFolder(), v.getContactName(), v.getContactRole(),
				v.getEmail(), v.getPhone(), v.getWebsite(), v.getAccountNumber(), v.isHandlesPhi(),
				v.getContractStart(), v.getContractEnd(), v.isAutoRenews(), v.getCancellationNoticeDays(),
				v.getMonthlyCost(), v.getPaymentTerms(), v.getBaaStatus(), v.getBaaSignedOn(), v.getNotes(),
				v.isActive(), v.getDocumentsJson() == null ? List.of() : v.getDocumentsJson(),
				v.getCreatedAt(), v.getUpdatedAt()
		);
	}

	private String blankToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}
}
