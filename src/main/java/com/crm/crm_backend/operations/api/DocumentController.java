package com.crm.crm_backend.operations.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.crm.crm_backend.operations.api.dto.OpsDtos.DraftRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.DraftResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.FileResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.FileTextResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.FileUpdateRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.FolderRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.FolderResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.FolderUpdateRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.TextDocumentRequest;
import com.crm.crm_backend.operations.application.DocumentService;
import com.crm.crm_backend.operations.application.DocumentService.Download;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

/** Document vault, vendor file cabinets, resources and training libraries. */
@RestController
@RequestMapping("/api/v1/operations")
public class DocumentController {

	private final DocumentService service;

	public DocumentController(DocumentService service) {
		this.service = service;
	}

	@GetMapping("/libraries/{space}/access")
	public Map<String, Boolean> access(@AuthenticationPrincipal UserPrincipal principal, @PathVariable String space) {
		return Map.of("canEdit", service.canWrite(principal, space));
	}

	@GetMapping("/folders")
	public List<FolderResponse> folders(@AuthenticationPrincipal UserPrincipal principal, @RequestParam String space,
			@RequestParam(required = false) UUID vendorId) {
		return service.folders(principal, space, vendorId);
	}

	@PostMapping("/folders")
	@ResponseStatus(HttpStatus.CREATED)
	public FolderResponse createFolder(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody FolderRequest body) {
		return service.createFolder(principal, body);
	}

	@PutMapping("/folders/{id}")
	public FolderResponse updateFolder(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody FolderUpdateRequest body) {
		return service.updateFolder(principal, id, body);
	}

	@DeleteMapping("/folders/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteFolder(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.deleteFolder(principal, id);
	}

	@GetMapping("/files")
	public List<FileResponse> files(@AuthenticationPrincipal UserPrincipal principal, @RequestParam String space,
			@RequestParam(required = false) UUID vendorId, @RequestParam(required = false) UUID staffId) {
		return service.files(principal, space, vendorId, staffId);
	}

	@PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public FileResponse upload(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam String space,
			@RequestPart("file") MultipartFile file,
			@RequestParam(required = false) UUID folderId,
			@RequestParam(required = false) UUID vendorId,
			@RequestParam(required = false) UUID staffId,
			@RequestParam(required = false) String category,
			@RequestParam(required = false) String docType,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresOn,
			@RequestParam(required = false) String notes,
			@RequestParam(required = false) String linkRef
	) {
		return service.upload(principal, space, file, folderId, vendorId, staffId, category, docType, expiresOn, notes, linkRef);
	}

	@PostMapping("/files/text")
	@ResponseStatus(HttpStatus.CREATED)
	public FileResponse createText(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody TextDocumentRequest body) {
		return service.createText(principal, body);
	}

	@PutMapping("/files/{id}")
	public FileResponse update(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody FileUpdateRequest body) {
		return service.update(principal, id, body);
	}

	@DeleteMapping("/files/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.delete(principal, id);
	}

	@GetMapping("/files/{id}/text")
	public FileTextResponse text(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		return service.text(principal, id);
	}

	@GetMapping("/files/{id}/content")
	public ResponseEntity<byte[]> content(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
			@RequestParam(defaultValue = "false") boolean download) {
		Download d = service.download(principal, id);
		ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
				.filename(d.name(), java.nio.charset.StandardCharsets.UTF_8).build();
		MediaType type;
		try {
			type = MediaType.parseMediaType(d.contentType());
		} catch (RuntimeException ex) {
			type = MediaType.APPLICATION_OCTET_STREAM;
		}
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.header("X-Content-Type-Options", "nosniff")
				.header("Content-Security-Policy", "sandbox")
				.contentType(type)
				.body(d.data());
	}

	@PostMapping("/drafts")
	public DraftResponse draft(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody DraftRequest body) {
		return service.draft(principal, body);
	}
}
