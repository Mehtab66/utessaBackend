package com.crm.crm_backend.operations.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.operations.api.dto.OpsDtos.InvoiceRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.InvoiceResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.SpecialtyRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.SpecialtyResponse;
import com.crm.crm_backend.operations.domain.OpsFile;
import com.crm.crm_backend.operations.domain.SpecialtyTemplate;
import com.crm.crm_backend.operations.domain.VendorInvoice;
import com.crm.crm_backend.operations.infrastructure.OpsFileRepository;
import com.crm.crm_backend.operations.infrastructure.SpecialtyTemplateRepository;
import com.crm.crm_backend.operations.infrastructure.VendorInvoiceRepository;
import com.crm.crm_backend.operations.infrastructure.VendorRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

/** Vendor invoices and specialty templates. */
@Service
public class VendorExtrasService {

	public static final List<String> INVOICE_STATUSES = List.of("Unpaid", "Paid", "Disputed", "Void");

	private final OpsSupport ops;
	private final OpsDefaultsService defaults;
	private final VendorRepository vendorRepository;
	private final VendorInvoiceRepository invoiceRepository;
	private final SpecialtyTemplateRepository specialtyRepository;
	private final OpsFileRepository fileRepository;

	public VendorExtrasService(
			OpsSupport ops,
			OpsDefaultsService defaults,
			VendorRepository vendorRepository,
			VendorInvoiceRepository invoiceRepository,
			SpecialtyTemplateRepository specialtyRepository,
			OpsFileRepository fileRepository
	) {
		this.ops = ops;
		this.defaults = defaults;
		this.vendorRepository = vendorRepository;
		this.invoiceRepository = invoiceRepository;
		this.specialtyRepository = specialtyRepository;
		this.fileRepository = fileRepository;
	}

	@Transactional(readOnly = true)
	public List<InvoiceResponse> invoices(UserPrincipal principal, UUID vendorId) {
		ops.require(principal, OpsSupport.VENDORS);
		List<VendorInvoice> list = vendorId == null
				? invoiceRepository.findByOrganizationIdOrderByInvoiceDateDesc(ops.orgId())
				: invoiceRepository.findByOrganizationIdAndVendorIdOrderByInvoiceDateDesc(ops.orgId(), vendorId);
		return list.stream().map(this::toInvoice).toList();
	}

	@Transactional
	public InvoiceResponse saveInvoice(UserPrincipal principal, UUID id, InvoiceRequest r) {
		ops.require(principal, OpsSupport.VENDORS);
		if (vendorRepository.findByIdAndOrganizationId(r.vendorId(), ops.orgId()).isEmpty()) {
			throw ApiException.notFound("Vendor not found.");
		}
		String status = OpsSupport.clean(r.status()) == null ? "Unpaid" : r.status().trim();
		if (!INVOICE_STATUSES.contains(status)) {
			throw ApiException.badRequest("Unknown invoice status: " + status);
		}
		VendorInvoice inv = id == null ? new VendorInvoice() : invoiceRepository.findByIdAndOrganizationId(id, ops.orgId())
				.orElseThrow(() -> ApiException.notFound("Invoice not found."));
		inv.setVendorId(r.vendorId());
		inv.setNumber(r.number().trim());
		inv.setInvoiceDate(r.invoiceDate());
		inv.setDueDate(r.dueDate());
		inv.setAmount(r.amount());
		inv.setDescription(OpsSupport.clean(r.description()));
		inv.setStatus(status);
		inv.setPaidOn("Paid".equals(status) ? r.paidOn() : null);
		inv.setPaidAmount("Paid".equals(status) ? (r.paidAmount() != null ? r.paidAmount() : r.amount()) : null);
		inv.setMethod(OpsSupport.clean(r.method()));
		inv.setReference(OpsSupport.clean(r.reference()));
		if (r.fileId() != null) {
			OpsFile f = fileRepository.findByIdAndOrganizationId(r.fileId(), ops.orgId())
					.orElseThrow(() -> ApiException.notFound("Attached file not found."));
			if (!r.vendorId().equals(f.getVendorId())) {
				throw ApiException.badRequest("The attached file belongs to another vendor.");
			}
		}
		inv.setFileId(r.fileId());
		return toInvoice(invoiceRepository.save(inv));
	}

	@Transactional
	public void deleteInvoice(UserPrincipal principal, UUID id) {
		ops.require(principal, OpsSupport.VENDORS);
		invoiceRepository.delete(invoiceRepository.findByIdAndOrganizationId(id, ops.orgId())
				.orElseThrow(() -> ApiException.notFound("Invoice not found.")));
	}

	@Transactional
	public List<SpecialtyResponse> specialties(UserPrincipal principal) {
		ops.require(principal, OpsSupport.VENDORS);
		defaults.ensureSpecialties(ops.orgId());
		return specialtyRepository.findByOrganizationIdOrderBySortOrderAscNameAsc(ops.orgId()).stream().map(this::toSpecialty).toList();
	}

	@Transactional
	public SpecialtyResponse saveSpecialty(UserPrincipal principal, UUID id, SpecialtyRequest r) {
		ops.require(principal, OpsSupport.VENDORS);
		SpecialtyTemplate s = id == null ? new SpecialtyTemplate() : requireSpecialty(id);
		String name = r.name().trim();
		if ((id == null || !s.getName().equalsIgnoreCase(name)) && specialtyRepository.existsByOrganizationIdAndNameIgnoreCase(ops.orgId(), name)) {
			throw ApiException.conflict("A specialty called " + name + " already exists.");
		}
		s.setName(name);
		s.setFolders(r.folders() == null ? new ArrayList<>() : new ArrayList<>(r.folders()));
		s.setCredentials(r.credentials() == null ? new ArrayList<>() : new ArrayList<>(r.credentials()));
		s.setIntake(r.intake() == null ? new ArrayList<>() : new ArrayList<>(r.intake()));
		if (id == null) {
			s.setSortOrder(100 + specialtyRepository.findByOrganizationIdOrderBySortOrderAscNameAsc(ops.orgId()).size());
		}
		return toSpecialty(specialtyRepository.save(s));
	}

	@Transactional
	public SpecialtyResponse cloneSpecialty(UserPrincipal principal, UUID id, String name) {
		SpecialtyTemplate src = requireSpecialty(id);
		return saveSpecialty(principal, null, new SpecialtyRequest(name, src.getFolders(), src.getCredentials(), src.getIntake()));
	}

	@Transactional
	public void deleteSpecialty(UserPrincipal principal, UUID id) {
		ops.require(principal, OpsSupport.VENDORS);
		specialtyRepository.delete(requireSpecialty(id));
	}

	private SpecialtyTemplate requireSpecialty(UUID id) {
		return specialtyRepository.findByIdAndOrganizationId(id, ops.orgId()).orElseThrow(() -> ApiException.notFound("Specialty template not found."));
	}

	private InvoiceResponse toInvoice(VendorInvoice i) {
		return new InvoiceResponse(i.getId(), i.getVendorId(), i.getNumber(), i.getInvoiceDate(), i.getDueDate(), i.getAmount(), i.getDescription(),
				i.getStatus(), i.getPaidOn(), i.getPaidAmount(), i.getMethod(), i.getReference(), i.getFileId(), i.getCreatedAt());
	}

	private SpecialtyResponse toSpecialty(SpecialtyTemplate s) {
		return new SpecialtyResponse(s.getId(), s.getName(), s.getFolders(), s.getCredentials(), s.getIntake(), s.isSystemTemplate(), s.getSortOrder());
	}
}
