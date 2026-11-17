package com.crm.crm_backend.crm.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.crm.api.dto.ActivityDtos.ActivityResponse;
import com.crm.crm_backend.crm.api.dto.CallDeskDtos.CallerLookupResponse;
import com.crm.crm_backend.crm.api.dto.CallDeskDtos.LogCallRequest;
import com.crm.crm_backend.crm.api.dto.CallDeskDtos.LogCallResponse;
import com.crm.crm_backend.crm.application.CallDeskService;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

/** Front-desk caller lookup (screen-pop) and call logging. */
@RestController
@RequestMapping("/api/v1/crm/call-desk")
public class CallDeskController {

	private final CallDeskService service;

	public CallDeskController(CallDeskService service) {
		this.service = service;
	}

	@GetMapping("/lookup")
	public CallerLookupResponse lookup(@AuthenticationPrincipal UserPrincipal principal, @RequestParam String phone) {
		return service.lookup(principal, phone);
	}

	@PostMapping("/calls")
	@ResponseStatus(HttpStatus.CREATED)
	public LogCallResponse logCall(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody LogCallRequest request) {
		return service.logCall(principal, request);
	}

	@GetMapping("/calls")
	public List<ActivityResponse> recent(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(defaultValue = "25") int limit
	) {
		return service.recentCalls(principal, limit);
	}
}
