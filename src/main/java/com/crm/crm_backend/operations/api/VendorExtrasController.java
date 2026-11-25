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

import com.crm.crm_backend.operations.api.dto.OpsDtos.CloneRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.InvoiceRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.InvoiceResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.SpecialtyRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.SpecialtyResponse;
import com.crm.crm_backend.operations.application.VendorExtrasService;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

/** Vendor invoices and specialty templates. */
@RestController
@RequestMapping("/api/v1/operations")
public class VendorExtrasController {

	private final VendorExtrasService service;

	public VendorExtrasController(VendorExtrasService service) {
		this.service = service;
	}

	@GetMapping("/vendor-invoices")
	public List<InvoiceResponse> invoices(@AuthenticationPrincipal UserPrincipal principal, @RequestParam(required = false) UUID vendorId) {
		return service.invoices(principal, vendorId);
	}

	@PostMapping("/vendor-invoices")
	@ResponseStatus(HttpStatus.CREATED)
	public InvoiceResponse createInvoice(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody InvoiceRequest body) {
		return service.saveInvoice(principal, null, body);
	}

	@PutMapping("/vendor-invoices/{id}")
	public InvoiceResponse updateInvoice(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody InvoiceRequest body) {
		return service.saveInvoice(principal, id, body);
	}

	@DeleteMapping("/vendor-invoices/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteInvoice(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.deleteInvoice(principal, id);
	}

	@GetMapping("/specialty-templates")
	public List<SpecialtyResponse> specialties(@AuthenticationPrincipal UserPrincipal principal) {
		return service.specialties(principal);
	}

	@PostMapping("/specialty-templates")
	@ResponseStatus(HttpStatus.CREATED)
	public SpecialtyResponse createSpecialty(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody SpecialtyRequest body) {
		return service.saveSpecialty(principal, null, body);
	}

	@PutMapping("/specialty-templates/{id}")
	public SpecialtyResponse updateSpecialty(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody SpecialtyRequest body) {
		return service.saveSpecialty(principal, id, body);
	}

	@PostMapping("/specialty-templates/{id}/clone")
	@ResponseStatus(HttpStatus.CREATED)
	public SpecialtyResponse cloneSpecialty(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody CloneRequest body) {
		return service.cloneSpecialty(principal, id, body.name());
	}

	@DeleteMapping("/specialty-templates/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteSpecialty(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.deleteSpecialty(principal, id);
	}
}
