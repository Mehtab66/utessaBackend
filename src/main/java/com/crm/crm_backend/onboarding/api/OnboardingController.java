package com.crm.crm_backend.onboarding.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.identity.api.dto.MeResponse;
import com.crm.crm_backend.identity.application.AuthService;
import com.crm.crm_backend.onboarding.api.dto.WelcomeRequest;
import com.crm.crm_backend.shared.security.UserPrincipal;

@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

	private final AuthService authService;

	public OnboardingController(AuthService authService) {
		this.authService = authService;
	}

	@PatchMapping("/welcome")
	public MeResponse welcome(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestBody WelcomeRequest request
	) {
		return authService.completeWelcome(
				principal,
				request.orgType(),
				request.ehrName(),
				request.enabledServices()
		);
	}
}
