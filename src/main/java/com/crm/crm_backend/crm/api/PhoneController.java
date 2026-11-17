package com.crm.crm_backend.crm.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.crm.crm_backend.crm.api.dto.PhoneDtos.ConfigRequest;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.FaxResponse;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.FileFaxRequest;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.ForwardFaxRequest;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.LogVoicemailRequest;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.MessageResponse;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.PhoneContact;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.PhoneOverview;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.SendTextRequest;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.ThreadResponse;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.ThreadSummary;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.VoicemailResponse;
import com.crm.crm_backend.crm.application.PhoneService;
import com.crm.crm_backend.crm.domain.Fax;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

/** Phone workspace: overview, contacts, texts, voicemail, fax and call-handling settings. */
@RestController
@RequestMapping("/api/v1/crm/phone")
public class PhoneController {

	private final PhoneService phone;

	public PhoneController(PhoneService phone) {
		this.phone = phone;
	}

	@GetMapping
	public PhoneOverview overview(@AuthenticationPrincipal UserPrincipal principal) {
		return phone.overview(principal);
	}

	@PutMapping("/config/me")
	public Map<String, Object> saveMine(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody ConfigRequest request) {
		return phone.saveMyConfig(principal, request);
	}

	@PutMapping("/config/practice")
	public Map<String, Object> savePractice(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody ConfigRequest request) {
		return phone.savePracticeConfig(principal, request);
	}

	@GetMapping("/contacts")
	public List<PhoneContact> contacts(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String group
	) {
		return phone.contacts(principal, q, group);
	}

	// ── texts ───────────────────────────────────────────────────────────

	@GetMapping("/threads")
	public List<ThreadSummary> threads(@AuthenticationPrincipal UserPrincipal principal) {
		return phone.threads(principal);
	}

	@GetMapping("/threads/{phone}")
	public ThreadResponse thread(@AuthenticationPrincipal UserPrincipal principal, @PathVariable("phone") String number) {
		return phone.thread(principal, number);
	}

	@PostMapping("/threads/{phone}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void read(@AuthenticationPrincipal UserPrincipal principal, @PathVariable("phone") String number) {
		phone.markRead(principal, number);
	}

	@PostMapping("/messages")
	@ResponseStatus(HttpStatus.CREATED)
	public MessageResponse send(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody SendTextRequest request) {
		return phone.sendText(principal, request);
	}

	// ── voicemail ───────────────────────────────────────────────────────

	@GetMapping("/voicemails")
	public List<VoicemailResponse> voicemails(@AuthenticationPrincipal UserPrincipal principal) {
		return phone.voicemails(principal);
	}

	@PostMapping("/voicemails")
	@ResponseStatus(HttpStatus.CREATED)
	public VoicemailResponse logVoicemail(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody LogVoicemailRequest request) {
		return phone.logVoicemail(principal, request);
	}

	@PostMapping("/voicemails/{id}/status")
	public VoicemailResponse voicemailStatus(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@RequestParam String status
	) {
		return phone.setVoicemailStatus(principal, id, status);
	}

	// ── fax ─────────────────────────────────────────────────────────────

	@GetMapping("/faxes")
	public List<FaxResponse> faxes(@AuthenticationPrincipal UserPrincipal principal) {
		return phone.faxes(principal);
	}

	@PostMapping(value = "/faxes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public FaxResponse createFax(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(defaultValue = "OUT") String direction,
			@RequestParam String faxNumber,
			@RequestParam(required = false) String partyName,
			@RequestParam(required = false) UUID patientId,
			@RequestParam(required = false) String subject,
			@RequestParam(required = false) String documentType,
			@RequestParam(defaultValue = "false") boolean coverPage,
			@RequestParam(required = false) String coverNote,
			@RequestParam("file") MultipartFile file
	) {
		return phone.createFax(principal, direction, faxNumber, partyName, patientId, subject, documentType, coverPage, coverNote, file);
	}

	@PostMapping("/faxes/{id}/file")
	public FaxResponse fileFax(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody FileFaxRequest request) {
		return phone.fileFax(principal, id, request);
	}

	@PostMapping("/faxes/{id}/forward")
	@ResponseStatus(HttpStatus.CREATED)
	public FaxResponse forwardFax(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id, @Valid @RequestBody ForwardFaxRequest request) {
		return phone.forwardFax(principal, id, request);
	}

	@GetMapping("/faxes/{id}/document")
	public ResponseEntity<byte[]> faxDocument(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		Fax f = phone.faxFile(principal, id);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
						.filename(f.getFileName() == null ? "fax" : f.getFileName()).build().toString())
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.header("X-Content-Type-Options", "nosniff")
				.contentType(MediaType.parseMediaType(f.getContentType()))
				.body(f.getFileData());
	}
}
