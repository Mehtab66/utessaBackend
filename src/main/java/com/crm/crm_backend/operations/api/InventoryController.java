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

import com.crm.crm_backend.operations.api.dto.OpsDtos.AdjustRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.AssetRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.AssetResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.InventoryAccess;
import com.crm.crm_backend.operations.api.dto.OpsDtos.InventoryRequestCreate;
import com.crm.crm_backend.operations.api.dto.OpsDtos.InventoryRequestResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.InventoryStatusRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.ItemRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.ItemResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.SecretResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.ServiceEntryRequest;
import com.crm.crm_backend.operations.application.InventoryService;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

/** Supplies, equipment, device logins and supply requests. */
@RestController
@RequestMapping("/api/v1/operations/inventory")
public class InventoryController {

	private final InventoryService service;

	public InventoryController(InventoryService service) {
		this.service = service;
	}

	@GetMapping("/access")
	public InventoryAccess access(@AuthenticationPrincipal UserPrincipal principal) {
		return new InventoryAccess(service.isAdmin(principal));
	}

	@GetMapping("/items")
	public List<ItemResponse> items(@AuthenticationPrincipal UserPrincipal principal) {
		return service.items(principal);
	}

	@PostMapping("/items")
	@ResponseStatus(HttpStatus.CREATED)
	public ItemResponse createItem(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody ItemRequest body) {
		return service.saveItem(principal, null, body);
	}

	@PutMapping("/items/{id}")
	public ItemResponse updateItem(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody ItemRequest body) {
		return service.saveItem(principal, id, body);
	}

	@PostMapping("/items/{id}/adjust")
	public ItemResponse adjust(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody AdjustRequest body) {
		return service.adjust(principal, id, body);
	}

	@DeleteMapping("/items/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteItem(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.deleteItem(principal, id);
	}

	@GetMapping("/assets")
	public List<AssetResponse> assets(@AuthenticationPrincipal UserPrincipal principal) {
		return service.assets(principal);
	}

	@PostMapping("/assets")
	@ResponseStatus(HttpStatus.CREATED)
	public AssetResponse createAsset(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody AssetRequest body) {
		return service.saveAsset(principal, null, body);
	}

	@PutMapping("/assets/{id}")
	public AssetResponse updateAsset(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody AssetRequest body) {
		return service.saveAsset(principal, id, body);
	}

	@PostMapping("/assets/{id}/service")
	public AssetResponse addService(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody ServiceEntryRequest body) {
		return service.addService(principal, id, body);
	}

	@PostMapping("/assets/{id}/password")
	public SecretResponse revealPassword(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		return service.revealPassword(principal, id);
	}

	@DeleteMapping("/assets/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteAsset(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.deleteAsset(principal, id);
	}

	@GetMapping("/requests")
	public List<InventoryRequestResponse> requests(@AuthenticationPrincipal UserPrincipal principal) {
		return service.requests(principal);
	}

	@PostMapping("/requests")
	@ResponseStatus(HttpStatus.CREATED)
	public InventoryRequestResponse createRequest(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody InventoryRequestCreate body) {
		return service.createRequest(principal, body);
	}

	@PostMapping("/requests/{id}/status")
	public InventoryRequestResponse changeStatus(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody InventoryStatusRequest body) {
		return service.changeStatus(principal, id, body);
	}
}
