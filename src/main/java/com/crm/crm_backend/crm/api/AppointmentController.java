package com.crm.crm_backend.crm.api;

import java.time.Instant;
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

import com.crm.crm_backend.crm.api.dto.AppointmentDtos.AppointmentRequest;
import com.crm.crm_backend.crm.api.dto.AppointmentDtos.AppointmentResponse;
import com.crm.crm_backend.crm.api.dto.AppointmentDtos.AppointmentStatusRequest;
import com.crm.crm_backend.crm.application.AppointmentService;
import com.crm.crm_backend.crm.domain.AppointmentStatus;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/crm/appointments")
public class AppointmentController {

	private final AppointmentService service;

	public AppointmentController(AppointmentService service) {
		this.service = service;
	}

	@GetMapping
	public List<AppointmentResponse> list(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to,
			@RequestParam(required = false) AppointmentStatus status,
			@RequestParam(required = false) UUID patientId,
			@RequestParam(required = false) String provider
	) {
		return service.list(principal, from, to, status, patientId, provider);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public AppointmentResponse create(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody AppointmentRequest request) {
		return service.create(principal, request);
	}

	@PutMapping("/{id}")
	public AppointmentResponse update(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody AppointmentRequest request
	) {
		return service.update(principal, id, request);
	}

	@PatchMapping("/{id}/status")
	public AppointmentResponse changeStatus(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody AppointmentStatusRequest request
	) {
		return service.changeStatus(principal, id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.delete(principal, id);
	}
}
