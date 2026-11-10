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

import com.crm.crm_backend.operations.api.dto.VendorDtos.VendorRequest;
import com.crm.crm_backend.operations.api.dto.VendorDtos.VendorResponse;
import com.crm.crm_backend.operations.application.VendorService;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/vendors")
public class VendorController {

	private final VendorService vendorService;

	public VendorController(VendorService vendorService) {
		this.vendorService = vendorService;
	}

	@GetMapping
	public List<VendorResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
		return vendorService.list(principal);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public VendorResponse create(
			@AuthenticationPrincipal UserPrincipal principal,
			@Valid @RequestBody VendorRequest request
	) {
		return vendorService.create(principal, request);
	}

	@PutMapping("/{id}")
	public VendorResponse update(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody VendorRequest request
	) {
		return vendorService.update(principal, id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		vendorService.delete(principal, id);
	}
}
