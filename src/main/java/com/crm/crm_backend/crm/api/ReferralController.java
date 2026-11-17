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
import com.crm.crm_backend.crm.api.dto.ReferralDtos.PartnerRequest;
import com.crm.crm_backend.crm.api.dto.ReferralDtos.PartnerResponse;
import com.crm.crm_backend.crm.api.dto.ReferralDtos.ReferralRequest;
import com.crm.crm_backend.crm.api.dto.ReferralDtos.ReferralResponse;
import com.crm.crm_backend.crm.api.dto.ReferralDtos.ReferralStatusRequest;
import com.crm.crm_backend.crm.application.ReferralService;
import com.crm.crm_backend.crm.domain.ReferralStatus;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

/** Referral queue and referring-partner directory. */
@RestController
@RequestMapping("/api/v1/crm")
public class ReferralController {

	private final ReferralService service;

	public ReferralController(ReferralService service) {
		this.service = service;
	}

	// ── referral partners ───────────────────────────────────────────────

	@GetMapping("/referral-partners")
	public List<PartnerResponse> partners(@AuthenticationPrincipal UserPrincipal principal) {
		return service.partners(principal);
	}

	@PostMapping("/referral-partners")
	@ResponseStatus(HttpStatus.CREATED)
	public PartnerResponse createPartner(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody PartnerRequest request) {
		return service.createPartner(principal, request);
	}

	@PutMapping("/referral-partners/{id}")
	public PartnerResponse updatePartner(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody PartnerRequest request
	) {
		return service.updatePartner(principal, id, request);
	}

	@DeleteMapping("/referral-partners/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deletePartner(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.deletePartner(principal, id);
	}

	// ── referrals ───────────────────────────────────────────────────────

	@GetMapping("/referrals")
	public List<ReferralResponse> list(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(required = false) ReferralStatus status,
			@RequestParam(required = false) UUID partnerId,
			@RequestParam(required = false) UUID patientId,
			@RequestParam(defaultValue = "false") boolean openOnly,
			@RequestParam(required = false) String q
	) {
		return service.list(principal, status, partnerId, patientId, openOnly, q);
	}

	@GetMapping("/referrals/{id}")
	public ReferralResponse get(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		return service.get(principal, id);
	}

	@GetMapping("/referrals/{id}/timeline")
	public List<ActivityResponse> timeline(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		return service.timeline(principal, id);
	}

	@PostMapping("/referrals")
	@ResponseStatus(HttpStatus.CREATED)
	public ReferralResponse create(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody ReferralRequest request) {
		return service.create(principal, request);
	}

	@PutMapping("/referrals/{id}")
	public ReferralResponse update(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody ReferralRequest request
	) {
		return service.update(principal, id, request);
	}

	@PatchMapping("/referrals/{id}/status")
	public ReferralResponse changeStatus(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody ReferralStatusRequest request
	) {
		return service.changeStatus(principal, id, request);
	}

	@DeleteMapping("/referrals/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.delete(principal, id);
	}
}
