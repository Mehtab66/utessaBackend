package com.crm.crm_backend.operations.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.operations.api.dto.OpsDtos.EnrollmentRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.EnrollmentResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.FacilityRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.FacilityResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.PayerRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.PayerResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.PrivilegeRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.PrivilegeResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.ProviderCompliance;
import com.crm.crm_backend.operations.application.ComplianceService;
import com.crm.crm_backend.operations.application.CredentialingService;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

/** Insurance credentialing, hospital privileges and the provider compliance roll-up. */
@RestController
@RequestMapping("/api/v1/operations")
public class CredentialingController {

	private final CredentialingService service;
	private final ComplianceService compliance;

	public CredentialingController(CredentialingService service, ComplianceService compliance) {
		this.service = service;
		this.compliance = compliance;
	}

	@GetMapping("/credentialing/options")
	public Map<String, List<String>> options() {
		return Map.of("enrollmentStatuses", CredentialingService.ENROLLMENT_STATUSES, "privilegeStatuses", CredentialingService.PRIVILEGE_STATUSES);
	}

	@GetMapping("/compliance/providers")
	public List<ProviderCompliance> providerCompliance(@AuthenticationPrincipal UserPrincipal principal) {
		return compliance.providers(principal);
	}

	@GetMapping("/payers")
	public List<PayerResponse> payers(@AuthenticationPrincipal UserPrincipal principal) {
		return service.payers(principal);
	}

	@PostMapping("/payers")
	@ResponseStatus(HttpStatus.CREATED)
	public PayerResponse createPayer(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody PayerRequest body) {
		return service.savePayer(principal, null, body);
	}

	@PutMapping("/payers/{id}")
	public PayerResponse updatePayer(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody PayerRequest body) {
		return service.savePayer(principal, id, body);
	}

	@DeleteMapping("/payers/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deletePayer(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.deletePayer(principal, id);
	}

	@GetMapping("/enrollments")
	public List<EnrollmentResponse> enrollments(@AuthenticationPrincipal UserPrincipal principal, @RequestParam(required = false) UUID staffId) {
		return service.enrollments(principal, staffId);
	}

	@PostMapping("/enrollments")
	@ResponseStatus(HttpStatus.CREATED)
	public List<EnrollmentResponse> createEnrollment(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody EnrollmentRequest body) {
		return service.createEnrollments(principal, body);
	}

	@PutMapping("/enrollments/{id}")
	public EnrollmentResponse updateEnrollment(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody EnrollmentRequest body) {
		return service.updateEnrollment(principal, id, body);
	}

	@DeleteMapping("/enrollments/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteEnrollment(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.deleteEnrollment(principal, id);
	}

	@GetMapping("/facilities")
	public List<FacilityResponse> facilities(@AuthenticationPrincipal UserPrincipal principal) {
		return service.facilities(principal);
	}

	@PostMapping("/facilities")
	@ResponseStatus(HttpStatus.CREATED)
	public FacilityResponse createFacility(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody FacilityRequest body) {
		return service.saveFacility(principal, null, body);
	}

	@PutMapping("/facilities/{id}")
	public FacilityResponse updateFacility(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody FacilityRequest body) {
		return service.saveFacility(principal, id, body);
	}

	@DeleteMapping("/facilities/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteFacility(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.deleteFacility(principal, id);
	}

	@GetMapping("/privileges")
	public List<PrivilegeResponse> privileges(@AuthenticationPrincipal UserPrincipal principal) {
		return service.privileges(principal);
	}

	@PostMapping("/privileges")
	@ResponseStatus(HttpStatus.CREATED)
	public PrivilegeResponse createPrivilege(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody PrivilegeRequest body) {
		return service.savePrivilege(principal, null, body);
	}

	@PutMapping("/privileges/{id}")
	public PrivilegeResponse updatePrivilege(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody PrivilegeRequest body) {
		return service.savePrivilege(principal, id, body);
	}

	@DeleteMapping("/privileges/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deletePrivilege(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.deletePrivilege(principal, id);
	}
}
