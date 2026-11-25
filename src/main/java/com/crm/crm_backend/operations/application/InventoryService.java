package com.crm.crm_backend.operations.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.crm.application.TokenCipher;
import com.crm.crm_backend.operations.api.dto.OpsDtos.AdjustRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.AssetRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.AssetResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.InventoryRequestCreate;
import com.crm.crm_backend.operations.api.dto.OpsDtos.InventoryRequestResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.InventoryStatusRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.ItemRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.ItemResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.SecretResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.ServiceEntryRequest;
import com.crm.crm_backend.operations.domain.Asset;
import com.crm.crm_backend.operations.domain.InventoryItem;
import com.crm.crm_backend.operations.domain.InventoryRequest;
import com.crm.crm_backend.operations.infrastructure.AssetRepository;
import com.crm.crm_backend.operations.infrastructure.InventoryItemRepository;
import com.crm.crm_backend.operations.infrastructure.InventoryRequestRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

/**
 * Supplies, equipment and device logins. Everyone in the practice can see stock and ask for
 * supplies; people with the vendor permission (owners, admins, managers) approve, order and receive.
 */
@Service
public class InventoryService {

	public static final List<String> REQUEST_STATUSES = List.of("Requested", "Approved", "Ordered", "Received", "Denied", "Cancelled");
	private static final Set<String> ADMIN_STATUSES = Set.of("Approved", "Ordered", "Received", "Denied");

	private final OpsSupport ops;
	private final InventoryItemRepository itemRepository;
	private final AssetRepository assetRepository;
	private final InventoryRequestRepository requestRepository;
	private final TokenCipher cipher;

	public InventoryService(OpsSupport ops, InventoryItemRepository itemRepository, AssetRepository assetRepository,
			InventoryRequestRepository requestRepository, TokenCipher cipher) {
		this.ops = ops;
		this.itemRepository = itemRepository;
		this.assetRepository = assetRepository;
		this.requestRepository = requestRepository;
		this.cipher = cipher;
	}

	public boolean isAdmin(UserPrincipal principal) {
		return ops.has(principal, OpsSupport.VENDORS);
	}

	/* ── supplies ────────────────────────────────────────────────────── */

	@Transactional(readOnly = true)
	public List<ItemResponse> items(UserPrincipal principal) {
		ops.require(principal, OpsSupport.DASH);
		return itemRepository.findByOrganizationIdOrderByNameAsc(ops.orgId()).stream().map(this::toItem).toList();
	}

	@Transactional
	public ItemResponse saveItem(UserPrincipal principal, UUID id, ItemRequest r) {
		ops.require(principal, OpsSupport.VENDORS);
		InventoryItem i = id == null ? new InventoryItem() : requireItem(id);
		i.setName(r.name().trim());
		i.setSku(OpsSupport.clean(r.sku()));
		i.setManufacturer(OpsSupport.clean(r.manufacturer()));
		i.setVendorName(OpsSupport.clean(r.vendorName()));
		i.setCategory(OpsSupport.clean(r.category()));
		i.setUnit(OpsSupport.clean(r.unit()));
		i.setPrice(r.price());
		i.setQty(Math.max(0, r.qty() == null ? 0 : r.qty()));
		i.setReorderLevel(r.reorderLevel());
		i.setParLevel(r.parLevel());
		i.setLocation(OpsSupport.clean(r.location()));
		i.setLot(OpsSupport.clean(r.lot()));
		i.setExpiresOn(r.expiresOn());
		i.setNotes(OpsSupport.clean(r.notes()));
		return toItem(itemRepository.save(i));
	}

	/** Use or count stock. Anyone can record that they used supplies; only admins can add stock. */
	@Transactional
	public ItemResponse adjust(UserPrincipal principal, UUID id, AdjustRequest r) {
		ops.require(principal, r.delta() > 0 ? OpsSupport.VENDORS : OpsSupport.DASH);
		InventoryItem i = requireItem(id);
		i.setQty(Math.max(0, i.getQty() + r.delta()));
		return toItem(itemRepository.save(i));
	}

	@Transactional
	public void deleteItem(UserPrincipal principal, UUID id) {
		ops.require(principal, OpsSupport.VENDORS);
		itemRepository.delete(requireItem(id));
	}

	/* ── equipment and devices ───────────────────────────────────────── */

	@Transactional(readOnly = true)
	public List<AssetResponse> assets(UserPrincipal principal) {
		ops.require(principal, OpsSupport.DASH);
		boolean admin = isAdmin(principal);
		return assetRepository.findByOrganizationIdOrderByAssetTypeAscNameAsc(ops.orgId()).stream().map(a -> toAsset(a, admin)).toList();
	}

	@Transactional
	public AssetResponse saveAsset(UserPrincipal principal, UUID id, AssetRequest r) {
		ops.require(principal, OpsSupport.VENDORS);
		Asset a = id == null ? new Asset() : requireAsset(id);
		if (r.assignedStaffId() != null) {
			ops.staff(r.assignedStaffId());
		}
		a.setTag(OpsSupport.clean(r.tag()) != null ? r.tag().trim() : id == null ? "AST-" + (1001 + assetRepository.countByOrganizationId(ops.orgId())) : a.getTag());
		a.setAssetType(r.assetType().trim());
		a.setName(r.name().trim());
		a.setManufacturer(OpsSupport.clean(r.manufacturer()));
		a.setSerial(OpsSupport.clean(r.serial()));
		a.setLocation(OpsSupport.clean(r.location()));
		a.setRoom(OpsSupport.clean(r.room()));
		a.setAssignedStaffId(r.assignedStaffId());
		a.setStatus(OpsSupport.clean(r.status()) == null ? "In use" : r.status().trim());
		a.setPurchasedOn(r.purchasedOn());
		a.setPrice(r.price());
		a.setWarrantyEnds(r.warrantyEnds());
		a.setPmMonths(r.pmMonths());
		a.setLastPmOn(r.lastPmOn());
		a.setVendorName(OpsSupport.clean(r.vendorName()));
		a.setNotes(OpsSupport.clean(r.notes()));
		a.setItHost(OpsSupport.clean(r.itHost()));
		a.setItIp(OpsSupport.clean(r.itIp()));
		a.setItUsername(OpsSupport.clean(r.itUsername()));
		a.setItOs(OpsSupport.clean(r.itOs()));
		a.setItRotatedOn(r.itRotatedOn());
		if (r.itPassword() != null) {
			a.setItPasswordEnc(r.itPassword().isEmpty() ? null : cipher.encrypt(r.itPassword()));
			if (!r.itPassword().isEmpty() && r.itRotatedOn() == null) {
				a.setItRotatedOn(LocalDate.now());
			}
		}
		return toAsset(assetRepository.save(a), true);
	}

	@Transactional
	public AssetResponse addService(UserPrincipal principal, UUID id, ServiceEntryRequest r) {
		ops.require(principal, OpsSupport.VENDORS);
		Asset a = requireAsset(id);
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("date", r.date().toString());
		s.put("kind", r.kind().trim());
		s.put("by", OpsSupport.clean(r.by()));
		s.put("cost", r.cost());
		s.put("notes", OpsSupport.clean(r.notes()));
		List<Map<String, Object>> list = OpsSupport.copy(a.getService());
		list.add(s);
		a.setService(list);
		if (r.kind().toLowerCase().contains("maintenance") && (a.getLastPmOn() == null || r.date().isAfter(a.getLastPmOn()))) {
			a.setLastPmOn(r.date());
		}
		return toAsset(assetRepository.save(a), true);
	}

	/** Shows a device password to an inventory admin. */
	@Transactional(readOnly = true)
	public SecretResponse revealPassword(UserPrincipal principal, UUID id) {
		ops.require(principal, OpsSupport.VENDORS);
		Asset a = requireAsset(id);
		if (a.getItPasswordEnc() == null) {
			throw ApiException.notFound("No password saved for this device.");
		}
		return new SecretResponse(cipher.decrypt(a.getItPasswordEnc()));
	}

	@Transactional
	public void deleteAsset(UserPrincipal principal, UUID id) {
		ops.require(principal, OpsSupport.VENDORS);
		assetRepository.delete(requireAsset(id));
	}

	/* ── supply requests ─────────────────────────────────────────────── */

	@Transactional(readOnly = true)
	public List<InventoryRequestResponse> requests(UserPrincipal principal) {
		ops.require(principal, OpsSupport.DASH);
		boolean admin = isAdmin(principal);
		return requestRepository.findByOrganizationIdOrderByCreatedAtDesc(ops.orgId()).stream()
				.filter(q -> admin || principal.userId().equals(q.getRequestedBy()))
				.map(q -> toRequest(q, principal)).toList();
	}

	@Transactional
	public InventoryRequestResponse createRequest(UserPrincipal principal, InventoryRequestCreate r) {
		ops.require(principal, OpsSupport.DASH);
		if (r.qty() == null || r.qty() < 1) {
			throw ApiException.badRequest("Quantity must be at least 1.");
		}
		InventoryRequest q = new InventoryRequest();
		if (r.itemId() != null) {
			InventoryItem item = requireItem(r.itemId());
			q.setItemId(item.getId());
			q.setName(item.getName());
			q.setSku(item.getSku());
			q.setUnit(item.getUnit());
			q.setVendorName(item.getVendorName());
			q.setEstimate(r.estimate() != null ? r.estimate() : item.getPrice() == null ? null : item.getPrice().multiply(BigDecimal.valueOf(r.qty())));
		} else {
			if (OpsSupport.clean(r.name()) == null) {
				throw ApiException.badRequest("Say what you need.");
			}
			q.setName(r.name().trim());
			q.setSku(OpsSupport.clean(r.sku()));
			q.setUnit(OpsSupport.clean(r.unit()));
			q.setEstimate(r.estimate());
		}
		q.setQty(r.qty());
		q.setNeededBy(r.neededBy());
		q.setPriority("Urgent".equalsIgnoreCase(r.priority()) ? "Urgent" : "Routine");
		q.setReason(OpsSupport.clean(r.reason()));
		q.setRequestedBy(principal.userId());
		String who = ops.actorName(principal);
		q.setRequestedByName(who);
		q.setStatus("Requested");
		q.getLog().add(log("Requested by " + who));
		return toRequest(requestRepository.save(q), principal);
	}

	@Transactional
	public InventoryRequestResponse changeStatus(UserPrincipal principal, UUID id, InventoryStatusRequest r) {
		ops.require(principal, OpsSupport.DASH);
		InventoryRequest q = requestRepository.findByIdAndOrganizationId(id, ops.orgId())
				.orElseThrow(() -> ApiException.notFound("Request not found."));
		String status = r.status().trim();
		if (!REQUEST_STATUSES.contains(status)) {
			throw ApiException.badRequest("Unknown status: " + status);
		}
		boolean admin = isAdmin(principal);
		if (ADMIN_STATUSES.contains(status) && !admin) {
			throw ApiException.forbidden("Only inventory admins can approve, order or receive.");
		}
		if ("Cancelled".equals(status) && !admin && !principal.userId().equals(q.getRequestedBy())) {
			throw ApiException.forbidden("You can only cancel your own requests.");
		}
		if ("Received".equals(q.getStatus()) || "Cancelled".equals(q.getStatus()) || "Denied".equals(q.getStatus())) {
			throw ApiException.badRequest("This request is closed.");
		}
		String who = ops.actorName(principal);
		List<Map<String, Object>> entries = OpsSupport.copy(q.getLog());
		switch (status) {
			case "Approved" -> entries.add(log("Approved by " + who));
			case "Denied" -> entries.add(log("Denied by " + who + (OpsSupport.clean(r.note()) == null ? "" : ": " + r.note().trim())));
			case "Cancelled" -> entries.add(log("Cancelled by " + who));
			case "Ordered" -> {
				q.setPoNumber(OpsSupport.clean(r.poNumber()));
				q.setVendorName(OpsSupport.clean(r.vendorName()) != null ? r.vendorName().trim() : q.getVendorName());
				q.setEta(r.eta());
				q.setUnitCost(r.unitCost());
				entries.add(log("Ordered" + (q.getVendorName() == null ? "" : " from " + q.getVendorName()) + (q.getPoNumber() == null ? "" : ", " + q.getPoNumber()) + " by " + who));
			}
			case "Received" -> {
				int received = r.receivedQty() == null ? q.getQty() : Math.max(0, r.receivedQty());
				if (q.getItemId() != null) {
					itemRepository.findByIdAndOrganizationId(q.getItemId(), ops.orgId()).ifPresent(item -> {
						item.setQty(item.getQty() + received);
						if (r.unitCost() != null) {
							item.setPrice(r.unitCost());
						}
						itemRepository.save(item);
					});
				}
				if (r.unitCost() != null) {
					q.setUnitCost(r.unitCost());
				}
				entries.add(log("Received " + received + (q.getItemId() != null ? " · added to stock" : "") + " by " + who));
			}
			default -> {
			}
		}
		if (OpsSupport.clean(r.note()) != null && !"Denied".equals(status)) {
			entries.add(log("Note: " + r.note().trim()));
		}
		q.setStatus(status);
		q.setLog(entries);
		return toRequest(requestRepository.save(q), principal);
	}

	/* ── helpers ─────────────────────────────────────────────────────── */

	private static Map<String, Object> log(String text) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("at", Instant.now().toString());
		m.put("text", text);
		return m;
	}

	private InventoryItem requireItem(UUID id) {
		return itemRepository.findByIdAndOrganizationId(id, ops.orgId()).orElseThrow(() -> ApiException.notFound("Item not found."));
	}

	private Asset requireAsset(UUID id) {
		return assetRepository.findByIdAndOrganizationId(id, ops.orgId()).orElseThrow(() -> ApiException.notFound("Equipment not found."));
	}

	private ItemResponse toItem(InventoryItem i) {
		return new ItemResponse(i.getId(), i.getName(), i.getSku(), i.getManufacturer(), i.getVendorName(), i.getCategory(), i.getUnit(),
				i.getPrice(), i.getQty(), i.getReorderLevel(), i.getParLevel(), i.getLocation(), i.getLot(), i.getExpiresOn(), i.getNotes(), i.getUpdatedAt());
	}

	private AssetResponse toAsset(Asset a, boolean admin) {
		LocalDate next = a.getPmMonths() == null || a.getPmMonths() <= 0 ? null
				: (a.getLastPmOn() != null ? a.getLastPmOn() : a.getPurchasedOn() != null ? a.getPurchasedOn() : LocalDate.now()).plusMonths(a.getPmMonths());
		return new AssetResponse(a.getId(), a.getTag(), a.getAssetType(), a.getName(), a.getManufacturer(), a.getSerial(), a.getLocation(),
				a.getRoom(), a.getAssignedStaffId(), a.getStatus(), a.getPurchasedOn(), admin ? a.getPrice() : null, a.getWarrantyEnds(),
				a.getPmMonths(), a.getLastPmOn(), next, a.getVendorName(), a.getNotes(),
				admin ? a.getItHost() : null, admin ? a.getItIp() : null, admin ? a.getItUsername() : null,
				admin && a.getItPasswordEnc() != null, admin ? a.getItOs() : null, admin ? a.getItRotatedOn() : null,
				OpsSupport.copy(a.getService()));
	}

	private InventoryRequestResponse toRequest(InventoryRequest q, UserPrincipal principal) {
		return new InventoryRequestResponse(q.getId(), q.getItemId(), q.getName(), q.getSku(), q.getQty(), q.getUnit(), q.getRequestedBy(),
				q.getRequestedByName(), q.getNeededBy(), q.getPriority(), q.getReason(), q.getStatus(), q.getEstimate(), q.getPoNumber(),
				q.getVendorName(), q.getEta(), q.getUnitCost(), OpsSupport.copy(q.getLog()),
				principal != null && principal.userId().equals(q.getRequestedBy()), q.getCreatedAt(), q.getUpdatedAt());
	}
}
