package com.crm.crm_backend.identity.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.identity.api.dto.AuthResponse;
import com.crm.crm_backend.identity.api.dto.LoginRequest;
import com.crm.crm_backend.identity.api.dto.MeResponse;
import com.crm.crm_backend.identity.api.dto.ResendCodeRequest;
import com.crm.crm_backend.identity.api.dto.SignupRequest;
import com.crm.crm_backend.identity.api.dto.SignupResponse;
import com.crm.crm_backend.identity.api.dto.VerifyEmailRequest;
import com.crm.crm_backend.identity.application.AuthService;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
		return authService.signup(request);
	}

	@PostMapping("/verify-email")
	public AuthResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
		return authService.verifyEmail(request);
	}

	@PostMapping("/resend-code")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void resendCode(@Valid @RequestBody ResendCodeRequest request) {
		authService.resendCode(request);
	}

	@PostMapping("/login")
	public AuthResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	@GetMapping("/me")
	public MeResponse me(@AuthenticationPrincipal UserPrincipal principal) {
		return authService.me(principal);
	}
}
