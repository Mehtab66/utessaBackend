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

import com.crm.crm_backend.operations.api.dto.LeadDtos.LeadRequest;
import com.crm.crm_backend.operations.api.dto.LeadDtos.LeadResponse;
import com.crm.crm_backend.operations.application.LeadService;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/leads")
public class LeadController {

	private final LeadService leadService;

	public LeadController(LeadService leadService) {
		this.leadService = leadService;
	}

	@GetMapping
	public List<LeadResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
		return leadService.list(principal);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public LeadResponse create(
			@AuthenticationPrincipal UserPrincipal principal,
			@Valid @RequestBody LeadRequest request
	) {
		return leadService.create(principal, request);
	}

	@PutMapping("/{id}")
	public LeadResponse update(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody LeadRequest request
	) {
		return leadService.update(principal, id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		leadService.delete(principal, id);
	}
}
