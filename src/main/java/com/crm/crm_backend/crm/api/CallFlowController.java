package com.crm.crm_backend.crm.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.crm.api.dto.ActivityDtos.ActivityResponse;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.CallAnalytics;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.DocumentCallRequest;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.IntakeRequest;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.IntakeResponse;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.LinkPhoneRequest;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.OpeningDay;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.ProviderOption;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.RecallAttemptRequest;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.RecallImportRequest;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.RecallImportResponse;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.RecallResponse;
import com.crm.crm_backend.crm.api.dto.PatientDtos.PatientResponse;
import com.crm.crm_backend.crm.application.CallFlowService;
import com.crm.crm_backend.crm.application.RecallService;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

/** Incoming-call flow, providers/openings, call analytics and the recall call list. */
@RestController
@RequestMapping("/api/v1/crm")
public class CallFlowController {

	private final CallFlowService callFlow;
	private final RecallService recalls;

	public CallFlowController(CallFlowService callFlow, RecallService recalls) {
		this.callFlow = callFlow;
		this.recalls = recalls;
	}

	@GetMapping("/call-desk/match")
	public List<PatientResponse> match(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam String firstName,
			@RequestParam String lastName,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateOfBirth
	) {
		return callFlow.matchByIdentity(principal, firstName, lastName, dateOfBirth);
	}

	@PostMapping("/patients/{id}/link-phone")
	public PatientResponse linkPhone(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody LinkPhoneRequest request
	) {
		return callFlow.linkPhone(principal, id, request.phone());
	}

	@PostMapping("/call-desk/document")
	@ResponseStatus(HttpStatus.CREATED)
	public ActivityResponse document(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody DocumentCallRequest request) {
		return callFlow.documentCall(principal, request);
	}

	@PostMapping("/call-desk/intake")
	@ResponseStatus(HttpStatus.CREATED)
	public IntakeResponse intake(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody IntakeRequest request) {
		return callFlow.intake(principal, request);
	}

	@GetMapping("/providers")
	public List<ProviderOption> providers(@AuthenticationPrincipal UserPrincipal principal) {
		return callFlow.providers(principal);
	}

	@GetMapping("/appointments/openings")
	public List<OpeningDay> openings(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(required = false) String provider,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(defaultValue = "40") int duration,
			@RequestParam(defaultValue = "3") int days
	) {
		return callFlow.openings(principal, provider, from, duration, days);
	}

	@GetMapping("/call-analytics")
	public CallAnalytics analytics(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) UUID userId,
			@RequestParam(required = false) String line,
			@RequestParam(required = false) String tz
	) {
		return callFlow.analytics(principal, from, to, userId, line, tz);
	}

	// ── recall call list ────────────────────────────────────────────────

	@GetMapping("/recalls")
	public List<RecallResponse> recalls(@AuthenticationPrincipal UserPrincipal principal) {
		return recalls.list(principal);
	}

	@PostMapping("/recalls/{id}/attempts")
	public RecallResponse attempt(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody RecallAttemptRequest request
	) {
		return recalls.attempt(principal, id, request);
	}

	@PostMapping("/recalls/{id}/reopen")
	public RecallResponse reopen(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		return recalls.reopen(principal, id);
	}

	@PostMapping("/recalls/import")
	public RecallImportResponse importRows(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody RecallImportRequest request) {
		return recalls.importRows(principal, request);
	}

	@DeleteMapping("/recalls/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		recalls.delete(principal, id);
	}
}
