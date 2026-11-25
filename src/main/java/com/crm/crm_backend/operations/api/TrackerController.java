package com.crm.crm_backend.operations.api;

import java.util.List;
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

import com.crm.crm_backend.operations.api.dto.OpsDtos.TrackerRowRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.TrackerRowResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.TrackerTabRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.TrackerTabResponse;
import com.crm.crm_backend.operations.application.TrackerService;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

/** Provider and staff trackers: CME, education, malpractice, peer review, contracts, onboarding, evaluations. */
@RestController
@RequestMapping("/api/v1/operations/trackers")
public class TrackerController {

	private final TrackerService service;

	public TrackerController(TrackerService service) {
		this.service = service;
	}

	@GetMapping
	public List<TrackerTabResponse> tabs(@AuthenticationPrincipal UserPrincipal principal, @RequestParam(defaultValue = "PROVIDER") String scope) {
		return service.tabs(principal, scope);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TrackerTabResponse createTab(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody TrackerTabRequest body) {
		return service.saveTab(principal, null, body);
	}

	@PutMapping("/{id}")
	public TrackerTabResponse updateTab(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody TrackerTabRequest body) {
		return service.saveTab(principal, id, body);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteTab(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.deleteTab(principal, id);
	}

	@GetMapping("/rows")
	public List<TrackerRowResponse> rows(@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(required = false) UUID tabId, @RequestParam(required = false) UUID staffId) {
		return service.rows(principal, tabId, staffId);
	}

	@PostMapping("/rows")
	@ResponseStatus(HttpStatus.CREATED)
	public TrackerRowResponse createRow(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody TrackerRowRequest body) {
		return service.saveRow(principal, null, body);
	}

	@PutMapping("/rows/{id}")
	public TrackerRowResponse updateRow(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody TrackerRowRequest body) {
		return service.saveRow(principal, id, body);
	}

	@DeleteMapping("/rows/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRow(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.deleteRow(principal, id);
	}
}
