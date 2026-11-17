package com.crm.crm_backend.crm.api;

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

import com.crm.crm_backend.crm.api.dto.CrmOverviewDtos.DashboardResponse;
import com.crm.crm_backend.crm.api.dto.CrmOverviewDtos.SearchResult;
import com.crm.crm_backend.crm.api.dto.CrmOverviewDtos.TeamMember;
import com.crm.crm_backend.crm.api.dto.IntegrationDtos.IntegrationRequest;
import com.crm.crm_backend.crm.api.dto.IntegrationDtos.IntegrationResponse;
import com.crm.crm_backend.crm.api.dto.TargetDtos.TargetRequest;
import com.crm.crm_backend.crm.api.dto.TargetDtos.TargetResponse;
import com.crm.crm_backend.crm.application.CrmOverviewService;
import com.crm.crm_backend.crm.application.IntegrationService;
import com.crm.crm_backend.crm.application.TargetService;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

/** CRM dashboard, global search, team directory, targets & quotas and integrations. */
@RestController
@RequestMapping("/api/v1/crm")
public class CrmAdminController {

	private final CrmOverviewService overviewService;
	private final TargetService targetService;
	private final IntegrationService integrationService;

	public CrmAdminController(
			CrmOverviewService overviewService,
			TargetService targetService,
			IntegrationService integrationService
	) {
		this.overviewService = overviewService;
		this.targetService = targetService;
		this.integrationService = integrationService;
	}

	@GetMapping("/dashboard")
	public DashboardResponse dashboard(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(required = false) String tz
	) {
		return overviewService.dashboard(principal, tz);
	}

	@GetMapping("/search")
	public List<SearchResult> search(@AuthenticationPrincipal UserPrincipal principal, @RequestParam String q) {
		return overviewService.search(principal, q);
	}

	@GetMapping("/team")
	public List<TeamMember> team(@AuthenticationPrincipal UserPrincipal principal) {
		return overviewService.team(principal);
	}

	// ── targets & quotas ────────────────────────────────────────────────

	@GetMapping("/targets")
	public List<TargetResponse> targets(@AuthenticationPrincipal UserPrincipal principal) {
		return targetService.list(principal);
	}

	@PostMapping("/targets")
	@ResponseStatus(HttpStatus.CREATED)
	public TargetResponse createTarget(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody TargetRequest request) {
		return targetService.create(principal, request);
	}

	@PutMapping("/targets/{id}")
	public TargetResponse updateTarget(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody TargetRequest request
	) {
		return targetService.update(principal, id, request);
	}

	@DeleteMapping("/targets/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteTarget(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		targetService.delete(principal, id);
	}

	// ── integrations (phone system, EHR) ────────────────────────────────

	@GetMapping("/integrations")
	public List<IntegrationResponse> integrations(@AuthenticationPrincipal UserPrincipal principal) {
		return integrationService.list(principal);
	}

	@PutMapping("/integrations/{kind}")
	public IntegrationResponse saveIntegration(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable String kind,
			@Valid @RequestBody IntegrationRequest request
	) {
		return integrationService.save(principal, kind, request);
	}
}
