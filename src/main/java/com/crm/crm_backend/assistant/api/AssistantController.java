package com.crm.crm_backend.assistant.api;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.assistant.application.AssistantService;
import com.crm.crm_backend.assistant.application.AssistantService.ChatReply;
import com.crm.crm_backend.assistant.application.AssistantService.ChatTurn;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

/** Top-bar "Ask Utessa AI" chat. Available to every signed-in practice member; the tools enforce access. */
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

	private final AssistantService service;

	public AssistantController(AssistantService service) {
		this.service = service;
	}

	@GetMapping("/status")
	public Map<String, Object> status() {
		return Map.of("configured", service.configured());
	}

	public record ChatRequest(List<ChatTurn> messages, String tz) {
	}

	@PostMapping("/chat")
	public ChatReply chat(@AuthenticationPrincipal UserPrincipal principal, @RequestBody ChatRequest request) {
		if (principal == null || principal.organizationId() == null) {
			throw ApiException.forbidden("Sign in to a practice to use the assistant.");
		}
		return service.chat(principal, request.messages(), request.tz());
	}
}
