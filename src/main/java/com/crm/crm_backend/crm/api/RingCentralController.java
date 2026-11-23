package com.crm.crm_backend.crm.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import com.crm.crm_backend.crm.api.dto.RingCentralDtos.AuthorizeResponse;
import com.crm.crm_backend.crm.api.dto.RingCentralDtos.RcCallResponse;
import com.crm.crm_backend.crm.api.dto.RingCentralDtos.RcStatus;
import com.crm.crm_backend.crm.api.dto.RingCentralDtos.SyncResult;
import com.crm.crm_backend.crm.api.dto.RingCentralDtos.WebPhoneResponse;
import com.crm.crm_backend.crm.application.RingCentralConfig;
import com.crm.crm_backend.crm.application.RingCentralService;
import com.crm.crm_backend.crm.application.RingCentralService.CallbackResult;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;

/** RingCentral phone system: connect, browser phone credentials, call log and sync. */
@RestController
@RequestMapping("/api/v1/crm/ringcentral")
public class RingCentralController {

	private final RingCentralService service;
	private final RingCentralConfig config;
	private final ObjectMapper json = new ObjectMapper();

	public RingCentralController(RingCentralService service, RingCentralConfig config) {
		this.service = service;
		this.config = config;
	}

	@GetMapping("/status")
	public RcStatus status(@AuthenticationPrincipal UserPrincipal principal) {
		return service.status(principal);
	}

	@GetMapping("/oauth/url")
	public AuthorizeResponse authorizeUrl(@AuthenticationPrincipal UserPrincipal principal) {
		return service.authorizeUrl(principal);
	}

	/**
	 * RingCentral redirects the sign-in popup here. Public (no Utessa session): the signed
	 * {@code state} carries the practice and user. Replies with a tiny page that notifies the
	 * Utessa window and closes itself.
	 */
	@GetMapping(value = "/oauth/callback", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> callback(
			@RequestParam(required = false) String code,
			@RequestParam(required = false) String state,
			@RequestParam(required = false) String error,
			@RequestParam(name = "error_description", required = false) String errorDescription
	) throws Exception {
		CallbackResult result = service.handleCallback(code, state, error, errorDescription);
		String payload = json.writeValueAsString(java.util.Map.of(
				"type", "utessa-ringcentral",
				"ok", result.ok(),
				"message", result.message()));
		String origin = json.writeValueAsString(config.frontendUrl());
		String page = """
				<!doctype html><html><head><meta charset="utf-8"><title>RingCentral</title>
				<meta name="viewport" content="width=device-width,initial-scale=1"></head>
				<body style="font-family:system-ui,sans-serif;padding:32px;color:#10132e">
				<h1 style="font-size:18px">%s</h1><p style="color:#555">You can close this window.</p>
				<script>
				(function(){var p=%s;try{if(window.opener&&!window.opener.closed){window.opener.postMessage(p,%s);}}catch(e){}
				setTimeout(function(){window.close();},%d);})();
				</script></body></html>
				""".formatted(HtmlUtils.htmlEscape(result.message()), payload, origin, result.ok() ? 600 : 6000);
		return ResponseEntity.ok()
				.header("Cache-Control", "no-store")
				.header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'")
				.body(page);
	}

	@PostMapping("/disconnect")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void disconnect(@AuthenticationPrincipal UserPrincipal principal) {
		service.disconnect(principal);
	}

	@PostMapping("/webphone")
	public WebPhoneResponse webPhone(@AuthenticationPrincipal UserPrincipal principal) {
		return service.webPhone(principal);
	}

	@GetMapping("/calls")
	public List<RcCallResponse> calls(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(defaultValue = "false") boolean missed
	) {
		return service.callLog(principal, limit, missed);
	}

	@PostMapping("/sync")
	public SyncResult sync(@AuthenticationPrincipal UserPrincipal principal) {
		return service.syncMine(principal);
	}
}
