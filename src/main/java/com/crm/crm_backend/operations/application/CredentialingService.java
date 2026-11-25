package com.crm.crm_backend.operations.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.operations.api.dto.OpsDtos.EnrollmentRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.EnrollmentResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.FacilityRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.FacilityResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.PayerRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.PayerResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.PrivilegeRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.PrivilegeResponse;
import com.crm.crm_backend.operations.domain.Facility;
import com.crm.crm_backend.operations.domain.Payer;
import com.crm.crm_backend.operations.domain.PayerEnrollment;
import com.crm.crm_backend.operations.domain.Privilege;
import com.crm.crm_backend.operations.domain.StaffMember;
import com.crm.crm_backend.operations.infrastructure.FacilityRepository;
import com.crm.crm_backend.operations.infrastructure.PayerEnrollmentRepository;
import com.crm.crm_backend.operations.infrastructure.PayerRepository;
import com.crm.crm_backend.operations.infrastructure.PrivilegeRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

/** Payer enrollment (insurance credentialing) and hospital privileges. */
@Service
public class CredentialingService {

	public static final List<String> ENROLLMENT_STATUSES = List.of(
			"Not started", "Application submitted", "In process", "Additional info requested", "Approved",
			"Denied", "Not participating (by choice)", "Terminated");
	public static final List<String> PRIVILEGE_STATUSES = List.of(
			"Not started", "Application submitted", "Credentials committee review", "MEC / board approval pending",
			"Granted", "Temporary privileges", "Denied", "Resigned / inactive");

	private final OpsSupport ops;
	private final OpsDefaultsService defaults;
	private final PayerRepository payerRepository;
	private final PayerEnrollmentRepository enrollmentRepository;
	private final FacilityRepository facilityRepository;
	private final PrivilegeRepository privilegeRepository;

	public CredentialingService(
			OpsSupport ops,
			OpsDefaultsService defaults,
			PayerRepository payerRepository,
			PayerEnrollmentRepository enrollmentRepository,
			FacilityRepository facilityRepository,
			PrivilegeRepository privilegeRepository
	) {
		this.ops = ops;
		this.defaults = defaults;
		this.payerRepository = payerRepository;
		this.enrollmentRepository = enrollmentRepository;
		this.facilityRepository = facilityRepository;
		this.privilegeRepository = privilegeRepository;
	}

	/* ── Payers ──────────────────────────────────────────────────────── */

	@Transactional
	public List<PayerResponse> payers(UserPrincipal principal) {
		ops.require(principal, OpsSupport.CREDS);
		defaults.ensurePayers(ops.orgId());
		return payerRepository.findByOrganizationIdOrderBySortOrderAscNameAsc(ops.orgId()).stream().map(this::toPayer).toList();
	}

	@Transactional
	public PayerResponse savePayer(UserPrincipal principal, UUID id, PayerRequest r) {
		ops.require(principal, OpsSupport.CREDS);
		Payer p = id == null ? new Payer() : requirePayer(id);
		p.setName(r.name().trim());
		p.setShortName(r.shortName().trim());
		p.setPayerType(OpsSupport.clean(r.payerType()));
		p.setMethod(OpsSupport.clean(r.method()));
		p.setTypicalDays(r.typicalDays());
		p.setRecredMonths(r.recredMonths());
		p.setActive(r.active() == null || r.active());
		if (r.sortOrder() != null) {
			p.setSortOrder(r.sortOrder());
		} else if (id == null) {
			p.setSortOrder(payerRepository.findByOrganizationIdOrderBySortOrderAscNameAsc(ops.orgId()).size() + 1);
		}
		return toPayer(payerRepository.save(p));
	}

	@Transactional
	public void deletePayer(UserPrincipal principal, UUID id) {
		ops.require(principal, OpsSupport.CREDS);
		payerRepository.delete(requirePayer(id));
	}

	/* ── Enrollments ─────────────────────────────────────────────────── */

	@Transactional(readOnly = true)
	public List<EnrollmentResponse> enrollments(UserPrincipal principal, UUID staffId) {
		ops.require(principal, OpsSupport.CREDS);
		List<PayerEnrollment> list = staffId == null
				? enrollmentRepository.findByOrganizationId(ops.orgId())
				: enrollmentRepository.findByOrganizationIdAndStaffId(ops.orgId(), staffId);
		return list.stream().map(this::toEnrollment).toList();
	}

	@Transactional
	public List<EnrollmentResponse> createEnrollments(UserPrincipal principal, EnrollmentRequest r) {
		ops.require(principal, OpsSupport.CREDS);
		requireStatus(r.status(), ENROLLMENT_STATUSES);
		StaffMember m = ops.staff(r.staffId());
		String actor = ops.actorName(principal);
		List<Payer> targets = new ArrayList<>();
		if (Boolean.TRUE.equals(r.allPayers())) {
			defaults.ensurePayers(ops.orgId());
			payerRepository.findByOrganizationIdOrderBySortOrderAscNameAsc(ops.orgId()).stream().filter(Payer::isActive).forEach(targets::add);
		} else {
			targets.add(requirePayer(r.payerId()));
		}
		List<EnrollmentResponse> out = new ArrayList<>();
		for (Payer payer : targets) {
			if (enrollmentRepository.existsByOrganizationIdAndStaffIdAndPayerId(ops.orgId(), m.getId(), payer.getId())) {
				if (Boolean.TRUE.equals(r.allPayers())) {
					continue;
				}
				throw ApiException.conflict(OpsSupport.displayName(m) + " already has an enrollment with " + payer.getName() + ".");
			}
			PayerEnrollment e = new PayerEnrollment();
			e.setStaffId(m.getId());
			e.setPayerId(payer.getId());
			apply(e, r, payer);
			e.getHistory().add(OpsSupport.historyEntry(e.getStatus(), noteOr(r.note(), "Enrollment record opened."), actor));
			out.add(toEnrollment(enrollmentRepository.save(e)));
		}
		return out;
	}

	@Transactional
	public EnrollmentResponse updateEnrollment(UserPrincipal principal, UUID id, EnrollmentRequest r) {
		ops.require(principal, OpsSupport.CREDS);
		requireStatus(r.status(), ENROLLMENT_STATUSES);
		PayerEnrollment e = enrollmentRepository.findByIdAndOrganizationId(id, ops.orgId())
				.orElseThrow(() -> ApiException.notFound("Enrollment not found."));
		String before = e.getStatus();
		apply(e, r, payerRepository.findByIdAndOrganizationId(e.getPayerId(), ops.orgId()).orElse(null));
		boolean changed = !Objects.equals(before, e.getStatus());
		if (changed || OpsSupport.clean(r.note()) != null) {
			List<Map<String, Object>> h = OpsSupport.copy(e.getHistory());
			h.add(OpsSupport.historyEntry(e.getStatus(), noteOr(r.note(), changed ? "Status changed from " + before + "." : ""), ops.actorName(principal)));
			e.setHistory(h);
		}
		return toEnrollment(enrollmentRepository.save(e));
	}

	@Transactional
	public void deleteEnrollment(UserPrincipal principal, UUID id) {
		ops.require(principal, OpsSupport.CREDS);
		enrollmentRepository.delete(enrollmentRepository.findByIdAndOrganizationId(id, ops.orgId())
				.orElseThrow(() -> ApiException.notFound("Enrollment not found.")));
	}

	/* ── Facilities ──────────────────────────────────────────────────── */

	@Transactional(readOnly = true)
	public List<FacilityResponse> facilities(UserPrincipal principal) {
		ops.require(principal, OpsSupport.CREDS);
		return facilityRepository.findByOrganizationIdOrderByNameAsc(ops.orgId()).stream().map(this::toFacility).toList();
	}

	@Transactional
	public FacilityResponse saveFacility(UserPrincipal principal, UUID id, FacilityRequest r) {
		ops.require(principal, OpsSupport.CREDS);
		Facility f = id == null ? new Facility() : requireFacility(id);
		f.setName(r.name().trim());
		f.setFacilityType(OpsSupport.clean(r.facilityType()));
		f.setContact(OpsSupport.clean(r.contact()));
		f.setCycleMonths(r.cycleMonths());
		return toFacility(facilityRepository.save(f));
	}

	@Transactional
	public void deleteFacility(UserPrincipal principal, UUID id) {
		ops.require(principal, OpsSupport.CREDS);
		facilityRepository.delete(requireFacility(id));
	}

	/* ── Privileges ──────────────────────────────────────────────────── */

	@Transactional(readOnly = true)
	public List<PrivilegeResponse> privileges(UserPrincipal principal) {
		ops.require(principal, OpsSupport.CREDS);
		return privilegeRepository.findByOrganizationId(ops.orgId()).stream().map(this::toPrivilege).toList();
	}

	@Transactional
	public PrivilegeResponse savePrivilege(UserPrincipal principal, UUID id, PrivilegeRequest r) {
		ops.require(principal, OpsSupport.CREDS);
		requireStatus(r.status(), PRIVILEGE_STATUSES);
		ops.staff(r.staffId());
		Facility facility = requireFacility(r.facilityId());
		Privilege p = id == null ? new Privilege() : privilegeRepository.findByIdAndOrganizationId(id, ops.orgId())
				.orElseThrow(() -> ApiException.notFound("Privilege record not found."));
		String before = p.getStatus();
		p.setStaffId(r.staffId());
		p.setFacilityId(facility.getId());
		p.setCategory(OpsSupport.clean(r.category()));
		p.setDepartment(OpsSupport.clean(r.department()));
		p.setPrivileges(OpsSupport.clean(r.privileges()));
		p.setStatus(r.status());
		p.setAppliedOn(r.appliedOn());
		p.setApprovedOn(r.approvedOn());
		p.setReappointDue(r.reappointDue());
		if (p.getReappointDue() == null && "Granted".equals(r.status()) && r.approvedOn() != null) {
			p.setReappointDue(r.approvedOn().plusMonths(facility.getCycleMonths() == null ? 24 : facility.getCycleMonths()));
		}
		p.setFppeStatus(OpsSupport.clean(r.fppeStatus()));
		p.setFppeDue(r.fppeDue());
		p.setOppeDue(r.oppeDue());
		p.setContact(OpsSupport.clean(r.contact()));
		p.setNotes(OpsSupport.clean(r.notes()));
		boolean changed = !Objects.equals(before, p.getStatus());
		if (changed || OpsSupport.clean(r.note()) != null) {
			List<Map<String, Object>> h = OpsSupport.copy(p.getHistory());
			h.add(OpsSupport.historyEntry(p.getStatus(), noteOr(r.note(), before == null ? "Privilege record opened." : changed ? "Status changed from " + before + "." : ""), ops.actorName(principal)));
			p.setHistory(h);
		}
		return toPrivilege(privilegeRepository.save(p));
	}

	@Transactional
	public void deletePrivilege(UserPrincipal principal, UUID id) {
		ops.require(principal, OpsSupport.CREDS);
		privilegeRepository.delete(privilegeRepository.findByIdAndOrganizationId(id, ops.orgId())
				.orElseThrow(() -> ApiException.notFound("Privilege record not found.")));
	}

	/* ── helpers ─────────────────────────────────────────────────────── */

	private void apply(PayerEnrollment e, EnrollmentRequest r, Payer payer) {
		e.setStatus(r.status());
		e.setProducts(OpsSupport.clean(r.products()) != null ? r.products().trim() : payer != null ? payer.getPayerType() : null);
		e.setEnrollmentType(OpsSupport.clean(r.enrollmentType()));
		e.setGroupInfo(OpsSupport.clean(r.groupInfo()));
		e.setMethod(OpsSupport.clean(r.method()) != null ? r.method().trim() : payer != null ? payer.getMethod() : null);
		e.setSubmittedOn(r.submittedOn());
		e.setReference(OpsSupport.clean(r.reference()));
		e.setProviderPin(OpsSupport.clean(r.providerPin()));
		e.setEffectiveOn(r.effectiveOn());
		e.setRecredDue(r.recredDue());
		if ("Approved".equals(r.status()) && e.getEffectiveOn() != null && e.getRecredDue() == null && payer != null && payer.getRecredMonths() != null) {
			e.setRecredDue(e.getEffectiveOn().plusMonths(payer.getRecredMonths()));
		}
		e.setFollowUpOn(r.followUpOn());
		e.setOwnerName(OpsSupport.clean(r.ownerName()));
		e.setNotes(OpsSupport.clean(r.notes()));
	}

	private static String noteOr(String note, String fallback) {
		String n = OpsSupport.clean(note);
		return n == null ? fallback : n;
	}

	private static void requireStatus(String status, List<String> allowed) {
		if (!allowed.contains(status)) {
			throw ApiException.badRequest("Unknown status: " + status);
		}
	}

	private Payer requirePayer(UUID id) {
		if (id == null) {
			throw ApiException.badRequest("Pick a payer.");
		}
		return payerRepository.findByIdAndOrganizationId(id, ops.orgId()).orElseThrow(() -> ApiException.notFound("Payer not found."));
	}

	private Facility requireFacility(UUID id) {
		return facilityRepository.findByIdAndOrganizationId(id, ops.orgId()).orElseThrow(() -> ApiException.notFound("Facility not found."));
	}

	private PayerResponse toPayer(Payer p) {
		return new PayerResponse(p.getId(), p.getName(), p.getShortName(), p.getPayerType(), p.getMethod(), p.getTypicalDays(), p.getRecredMonths(), p.isActive(), p.getSortOrder());
	}

	private EnrollmentResponse toEnrollment(PayerEnrollment e) {
		return new EnrollmentResponse(e.getId(), e.getStaffId(), e.getPayerId(), e.getStatus(), e.getProducts(), e.getEnrollmentType(),
				e.getGroupInfo(), e.getMethod(), e.getSubmittedOn(), e.getReference(), e.getProviderPin(), e.getEffectiveOn(),
				e.getRecredDue(), e.getFollowUpOn(), e.getOwnerName(), e.getNotes(), OpsSupport.copy(e.getHistory()), e.getCreatedAt(), e.getUpdatedAt());
	}

	private FacilityResponse toFacility(Facility f) {
		return new FacilityResponse(f.getId(), f.getName(), f.getFacilityType(), f.getContact(), f.getCycleMonths());
	}

	private PrivilegeResponse toPrivilege(Privilege p) {
		return new PrivilegeResponse(p.getId(), p.getStaffId(), p.getFacilityId(), p.getCategory(), p.getDepartment(), p.getPrivileges(),
				p.getStatus(), p.getAppliedOn(), p.getApprovedOn(), p.getReappointDue(), p.getFppeStatus(), p.getFppeDue(), p.getOppeDue(),
				p.getContact(), p.getNotes(), OpsSupport.copy(p.getHistory()), p.getCreatedAt(), p.getUpdatedAt());
	}
}
