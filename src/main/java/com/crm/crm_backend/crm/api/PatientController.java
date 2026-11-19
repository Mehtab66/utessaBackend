package com.crm.crm_backend.crm.api;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.crm.api.dto.ActivityDtos.ActivityRequest;
import com.crm.crm_backend.crm.api.dto.ActivityDtos.ActivityResponse;
import com.crm.crm_backend.crm.api.dto.PatientDtos.BulkRequest;
import com.crm.crm_backend.crm.api.dto.PatientDtos.BulkResponse;
import com.crm.crm_backend.crm.api.dto.PatientDtos.ConvertRequest;
import com.crm.crm_backend.crm.api.dto.PatientDtos.ImportRequest;
import com.crm.crm_backend.crm.api.dto.PatientDtos.ImportResponse;
import com.crm.crm_backend.crm.api.dto.PatientDtos.DuplicateMatch;
import com.crm.crm_backend.crm.api.dto.PatientDtos.PatientRequest;
import com.crm.crm_backend.crm.api.dto.PatientDtos.PatientResponse;
import com.crm.crm_backend.crm.api.dto.PatientDtos.StatusChangeRequest;
import com.crm.crm_backend.crm.application.PatientService;
import com.crm.crm_backend.crm.application.PatientService.PatientFilter;
import com.crm.crm_backend.crm.domain.LeadStatus;
import com.crm.crm_backend.crm.domain.Lifecycle;
import com.crm.crm_backend.crm.domain.Priority;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

/** Patient leads and patients (one record that moves LEAD -> PATIENT). */
@RestController
@RequestMapping("/api/v1/crm/patients")
public class PatientController {

	private final PatientService service;

	public PatientController(PatientService service) {
		this.service = service;
	}

	@GetMapping
	public List<PatientResponse> list(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(required = false) Lifecycle lifecycle,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) Priority priority,
			@RequestParam(required = false) UUID ownerUserId,
			@RequestParam(required = false) UUID referralPartnerId,
			@RequestParam(required = false) String source,
			@RequestParam(required = false) String followUp,
			@RequestParam(required = false) String q
	) {
		List<LeadStatus> statuses = status == null || status.isBlank() ? List.of()
				: Arrays.stream(status.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(LeadStatus::valueOf).toList();
		return service.list(principal,
				new PatientFilter(lifecycle, statuses, priority, ownerUserId, referralPartnerId, source, followUp, q));
	}

	@GetMapping("/duplicates")
	public List<DuplicateMatch> duplicates(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(required = false) String phone,
			@RequestParam(required = false) String email,
			@RequestParam(required = false) UUID excludeId
	) {
		return service.duplicates(principal, phone, email, excludeId);
	}

	@GetMapping("/{id}")
	public PatientResponse get(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		return service.get(principal, id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public PatientResponse create(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(defaultValue = "false") boolean allowDuplicate,
			@Valid @RequestBody PatientRequest request
	) {
		return service.create(principal, request, allowDuplicate);
	}

	@PutMapping("/{id}")
	public PatientResponse update(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody PatientRequest request
	) {
		return service.update(principal, id, request);
	}

	@PatchMapping("/{id}/status")
	public PatientResponse changeStatus(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody StatusChangeRequest request
	) {
		return service.changeStatus(principal, id, request);
	}

	@PostMapping("/{id}/convert")
	public PatientResponse convert(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody(required = false) ConvertRequest request
	) {
		return service.convert(principal, id, request);
	}

	@PostMapping("/{id}/ehr-sync")
	public PatientResponse ehrSync(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		return service.requestEhrSync(principal, id);
	}

	@GetMapping("/{id}/timeline")
	public List<ActivityResponse> timeline(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		return service.timeline(principal, id);
	}

	@PostMapping("/{id}/activities")
	@ResponseStatus(HttpStatus.CREATED)
	public ActivityResponse addActivity(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody ActivityRequest request
	) {
		return service.addActivity(principal, id, request);
	}

	/** Bulk-adds leads read from a CSV / Excel file. */
	@PostMapping("/import")
	public ImportResponse importLeads(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody ImportRequest request) {
		return service.importLeads(principal, request);
	}

	@PostMapping("/bulk")
	public BulkResponse bulk(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody BulkRequest request) {
		return service.bulk(principal, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.delete(principal, id);
	}
}
