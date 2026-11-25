package com.crm.crm_backend.operations.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response shapes for the Practice Operations suite. */
public final class OpsDtos {

	private OpsDtos() {
	}

	/* ── Insurance credentialing ───────────────────────────────────────── */

	public record PayerRequest(
			@NotBlank @Size(max = 200) String name,
			@NotBlank @Size(max = 60) String shortName,
			@Size(max = 60) String payerType,
			@Size(max = 200) String method,
			Integer typicalDays,
			Integer recredMonths,
			Boolean active,
			Integer sortOrder
	) {
	}

	public record PayerResponse(
			UUID id, String name, String shortName, String payerType, String method,
			Integer typicalDays, Integer recredMonths, boolean active, int sortOrder
	) {
	}

	public record EnrollmentRequest(
			@NotNull UUID staffId,
			UUID payerId,
			/** Create one record for every active payer the provider is not enrolled with yet. */
			Boolean allPayers,
			@NotBlank @Size(max = 60) String status,
			@Size(max = 200) String products,
			@Size(max = 150) String enrollmentType,
			@Size(max = 255) String groupInfo,
			@Size(max = 200) String method,
			LocalDate submittedOn,
			@Size(max = 100) String reference,
			@Size(max = 100) String providerPin,
			LocalDate effectiveOn,
			LocalDate recredDue,
			LocalDate followUpOn,
			@Size(max = 150) String ownerName,
			String notes,
			/** Optional note saved to the history with this update. */
			String note
	) {
	}

	public record EnrollmentResponse(
			UUID id, UUID staffId, UUID payerId, String status, String products, String enrollmentType,
			String groupInfo, String method, LocalDate submittedOn, String reference, String providerPin,
			LocalDate effectiveOn, LocalDate recredDue, LocalDate followUpOn, String ownerName, String notes,
			List<Map<String, Object>> history, Instant createdAt, Instant updatedAt
	) {
	}

	/* ── Hospital privileges ───────────────────────────────────────────── */

	public record FacilityRequest(
			@NotBlank @Size(max = 200) String name,
			@Size(max = 80) String facilityType,
			@Size(max = 255) String contact,
			Integer cycleMonths
	) {
	}

	public record FacilityResponse(UUID id, String name, String facilityType, String contact, Integer cycleMonths) {
	}

	public record PrivilegeRequest(
			@NotNull UUID staffId,
			@NotNull UUID facilityId,
			@Size(max = 120) String category,
			@Size(max = 150) String department,
			String privileges,
			@NotBlank @Size(max = 60) String status,
			LocalDate appliedOn,
			LocalDate approvedOn,
			LocalDate reappointDue,
			@Size(max = 40) String fppeStatus,
			LocalDate fppeDue,
			LocalDate oppeDue,
			@Size(max = 255) String contact,
			String notes,
			String note
	) {
	}

	public record PrivilegeResponse(
			UUID id, UUID staffId, UUID facilityId, String category, String department, String privileges,
			String status, LocalDate appliedOn, LocalDate approvedOn, LocalDate reappointDue, String fppeStatus,
			LocalDate fppeDue, LocalDate oppeDue, String contact, String notes, List<Map<String, Object>> history,
			Instant createdAt, Instant updatedAt
	) {
	}

	/* ── Trackers ──────────────────────────────────────────────────────── */

	public record TrackerTabRequest(
			@NotBlank @Size(max = 20) String scope,
			@NotBlank @Size(max = 120) String name,
			@Size(max = 80) String groupName,
			@Size(max = 500) String description,
			List<Map<String, Object>> fields,
			Map<String, Object> goal,
			Boolean hidden,
			Integer sortOrder
	) {
	}

	public record TrackerTabResponse(
			UUID id, String code, String scope, String name, String groupName, String description,
			List<Map<String, Object>> fields, Map<String, Object> goal, boolean standard, boolean hidden, int sortOrder
	) {
	}

	public record TrackerRowRequest(@NotNull UUID tabId, @NotNull UUID staffId, Map<String, Object> vals) {
	}

	public record TrackerRowResponse(UUID id, UUID tabId, UUID staffId, Map<String, Object> vals, Instant createdAt, Instant updatedAt) {
	}

	/* ── Provider compliance ───────────────────────────────────────────── */

	public record ComplianceItem(
			String kind,
			String label,
			/** EXPIRED, ACTION, MISSING, DUE (within 90 days), OK. */
			String state,
			LocalDate dueOn,
			Long days,
			String detail
	) {
	}

	public record ProviderCompliance(
			UUID staffId,
			String name,
			String status,
			int readiness,
			List<ComplianceItem> openItems,
			int checks,
			int checksOk,
			int payersApproved,
			int payersTotal,
			int privilegesGranted,
			int privilegesTotal,
			double cmeCredits,
			double cmeNeeded,
			LocalDate cmeCycleEnds
	) {
	}

	/* ── Folders, files and written documents ──────────────────────────── */

	public record FolderRequest(
			@NotBlank @Size(max = 20) String space,
			UUID vendorId,
			UUID parentId,
			@NotBlank @Size(max = 150) String name
	) {
	}

	public record FolderUpdateRequest(@Size(max = 150) String name, UUID parentId, Boolean moveToTop) {
	}

	public record FolderResponse(UUID id, String space, UUID vendorId, UUID parentId, String name, boolean locked) {
	}

	public record FileUpdateRequest(
			UUID folderId,
			Boolean moveToTop,
			@Size(max = 255) String name,
			@Size(max = 120) String category,
			@Size(max = 200) String docType,
			LocalDate expiresOn,
			Boolean clearExpiry,
			String notes,
			UUID staffId,
			@Size(max = 120) String linkRef,
			String text
	) {
	}

	public record TextDocumentRequest(
			@NotBlank @Size(max = 20) String space,
			UUID folderId,
			UUID vendorId,
			@NotBlank @Size(max = 255) String name,
			@Size(max = 200) String docType,
			@NotBlank String text
	) {
	}

	public record FileResponse(
			UUID id, String space, UUID folderId, UUID vendorId, UUID staffId, String category, String docType,
			String name, String contentType, Long sizeBytes, boolean written, LocalDate expiresOn, String notes,
			String linkRef, String uploadedBy, Instant createdAt, Instant updatedAt
	) {
	}

	public record FileTextResponse(UUID id, String name, String text) {
	}

	public record DraftRequest(
			@NotBlank @Size(max = 20) String space,
			@NotBlank @Size(max = 500) String prompt,
			@Size(max = 20) String type,
			@Size(max = 120) String audience,
			@Size(max = 10) String length,
			Boolean quiz,
			Boolean signature
	) {
	}

	public record DraftResponse(String title, String text, boolean matched) {
	}

	/* ── Vendors: invoices and specialty templates ─────────────────────── */

	public record InvoiceRequest(
			@NotNull UUID vendorId,
			@NotBlank @Size(max = 100) String number,
			LocalDate invoiceDate,
			LocalDate dueDate,
			BigDecimal amount,
			@Size(max = 500) String description,
			@Size(max = 30) String status,
			LocalDate paidOn,
			BigDecimal paidAmount,
			@Size(max = 60) String method,
			@Size(max = 100) String reference,
			UUID fileId
	) {
	}

	public record InvoiceResponse(
			UUID id, UUID vendorId, String number, LocalDate invoiceDate, LocalDate dueDate, BigDecimal amount,
			String description, String status, LocalDate paidOn, BigDecimal paidAmount, String method,
			String reference, UUID fileId, Instant createdAt
	) {
	}

	public record SpecialtyRequest(
			@NotBlank @Size(max = 120) String name,
			List<Map<String, Object>> folders,
			List<String> credentials,
			List<String> intake
	) {
	}

	public record SpecialtyResponse(
			UUID id, String name, List<Map<String, Object>> folders, List<String> credentials, List<String> intake,
			boolean systemTemplate, int sortOrder
	) {
	}

	public record CloneRequest(@NotBlank @Size(max = 120) String name) {
	}

	/* ── Inventory ─────────────────────────────────────────────────────── */

	public record ItemRequest(
			@NotBlank @Size(max = 255) String name,
			@Size(max = 100) String sku,
			@Size(max = 150) String manufacturer,
			@Size(max = 150) String vendorName,
			@Size(max = 100) String category,
			@Size(max = 40) String unit,
			BigDecimal price,
			Integer qty,
			Integer reorderLevel,
			Integer parLevel,
			@Size(max = 150) String location,
			@Size(max = 80) String lot,
			LocalDate expiresOn,
			String notes
	) {
	}

	public record ItemResponse(
			UUID id, String name, String sku, String manufacturer, String vendorName, String category, String unit,
			BigDecimal price, int qty, Integer reorderLevel, Integer parLevel, String location, String lot,
			LocalDate expiresOn, String notes, Instant updatedAt
	) {
	}

	public record AdjustRequest(@NotNull Integer delta, String note) {
	}

	public record AssetRequest(
			@Size(max = 60) String tag,
			@NotBlank @Size(max = 100) String assetType,
			@NotBlank @Size(max = 255) String name,
			@Size(max = 150) String manufacturer,
			@Size(max = 120) String serial,
			@Size(max = 150) String location,
			@Size(max = 120) String room,
			UUID assignedStaffId,
			@Size(max = 40) String status,
			LocalDate purchasedOn,
			BigDecimal price,
			LocalDate warrantyEnds,
			Integer pmMonths,
			LocalDate lastPmOn,
			@Size(max = 150) String vendorName,
			String notes,
			@Size(max = 150) String itHost,
			@Size(max = 80) String itIp,
			@Size(max = 150) String itUsername,
			/** Write-only. Null keeps the stored password, an empty string clears it. */
			@Size(max = 255) String itPassword,
			@Size(max = 120) String itOs,
			LocalDate itRotatedOn
	) {
	}

	public record AssetResponse(
			UUID id, String tag, String assetType, String name, String manufacturer, String serial, String location,
			String room, UUID assignedStaffId, String status, LocalDate purchasedOn, BigDecimal price,
			LocalDate warrantyEnds, Integer pmMonths, LocalDate lastPmOn, LocalDate nextPmDue, String vendorName,
			String notes, String itHost, String itIp, String itUsername, boolean hasPassword, String itOs,
			LocalDate itRotatedOn, List<Map<String, Object>> service
	) {
	}

	public record ServiceEntryRequest(
			@NotNull LocalDate date,
			@NotBlank @Size(max = 120) String kind,
			@Size(max = 150) String by,
			BigDecimal cost,
			String notes
	) {
	}

	public record SecretResponse(String password) {
	}

	public record InventoryRequestCreate(
			UUID itemId,
			@Size(max = 255) String name,
			@Size(max = 100) String sku,
			@NotNull Integer qty,
			@Size(max = 40) String unit,
			LocalDate neededBy,
			@Size(max = 20) String priority,
			String reason,
			BigDecimal estimate
	) {
	}

	public record InventoryStatusRequest(
			@NotBlank @Size(max = 30) String status,
			@Size(max = 80) String poNumber,
			@Size(max = 150) String vendorName,
			LocalDate eta,
			BigDecimal unitCost,
			Integer receivedQty,
			String note
	) {
	}

	public record InventoryRequestResponse(
			UUID id, UUID itemId, String name, String sku, int qty, String unit, UUID requestedBy,
			String requestedByName, LocalDate neededBy, String priority, String reason, String status,
			BigDecimal estimate, String poNumber, String vendorName, LocalDate eta, BigDecimal unitCost,
			List<Map<String, Object>> log, boolean mine, Instant createdAt, Instant updatedAt
	) {
	}

	public record InventoryAccess(boolean admin) {
	}
}
