package com.crm.crm_backend.crm.api;

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

import com.crm.crm_backend.crm.api.dto.ActivityDtos.ActivityResponse;
import com.crm.crm_backend.crm.api.dto.OpportunityDtos.OpportunityRequest;
import com.crm.crm_backend.crm.api.dto.OpportunityDtos.OpportunityResponse;
import com.crm.crm_backend.crm.api.dto.OpportunityDtos.StageRequest;
import com.crm.crm_backend.crm.application.OpportunityService;
import com.crm.crm_backend.crm.domain.OpportunityStage;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/crm/opportunities")
public class OpportunityController {

	private final OpportunityService service;

	public OpportunityController(OpportunityService service) {
		this.service = service;
	}

	@GetMapping
	public List<OpportunityResponse> list(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(required = false) OpportunityStage stage,
			@RequestParam(required = false) UUID ownerUserId,
			@RequestParam(required = false) UUID patientId,
			@RequestParam(required = false) String q
	) {
		return service.list(principal, stage, ownerUserId, patientId, q);
	}

	@GetMapping("/{id}")
	public OpportunityResponse get(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		return service.get(principal, id);
	}

	@GetMapping("/{id}/timeline")
	public List<ActivityResponse> timeline(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		return service.timeline(principal, id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public OpportunityResponse create(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody OpportunityRequest request) {
		return service.create(principal, request);
	}

	@PutMapping("/{id}")
	public OpportunityResponse update(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody OpportunityRequest request
	) {
		return service.update(principal, id, request);
	}

	@PatchMapping("/{id}/stage")
	public OpportunityResponse changeStage(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody StageRequest request
	) {
		return service.changeStage(principal, id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.delete(principal, id);
	}
}
