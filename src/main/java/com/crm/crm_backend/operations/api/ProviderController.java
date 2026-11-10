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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.operations.api.dto.StaffDtos.StaffRequest;
import com.crm.crm_backend.operations.api.dto.StaffDtos.StaffResponse;
import com.crm.crm_backend.operations.application.StaffService;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/providers")
public class ProviderController {

	private final StaffService staffService;

	public ProviderController(StaffService staffService) {
		this.staffService = staffService;
	}

	@GetMapping
	public List<StaffResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
		return staffService.list(principal, true);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public StaffResponse create(
			@AuthenticationPrincipal UserPrincipal principal,
			@Valid @RequestBody StaffRequest request
	) {
		return staffService.create(principal, request, true);
	}

	@PutMapping("/{id}")
	public StaffResponse update(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody StaffRequest request
	) {
		return staffService.update(principal, id, request, true);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		staffService.delete(principal, id, true);
	}
}
