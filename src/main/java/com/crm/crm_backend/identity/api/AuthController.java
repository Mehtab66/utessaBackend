package com.crm.crm_backend.identity.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import com.crm.crm_backend.shared.security.SessionCookieService;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthService authService;
	private final SessionCookieService sessionCookieService;

	public AuthController(AuthService authService, SessionCookieService sessionCookieService) {
		this.authService = authService;
		this.sessionCookieService = sessionCookieService;
	}

	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
		return authService.signup(request);
	}

	@PostMapping("/verify-email")
	public ResponseEntity<AuthResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
		return withSession(authService.verifyEmail(request));
	}

	@PostMapping("/resend-code")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void resendCode(@Valid @RequestBody ResendCodeRequest request) {
		authService.resendCode(request);
	}

	@PostMapping("/login")
	public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
		return withSession(authService.login(request));
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public ResponseEntity<Void> logout() {
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, sessionCookieService.clear().toString())
				.build();
	}

	@GetMapping("/me")
	public MeResponse me(@AuthenticationPrincipal UserPrincipal principal) {
		return authService.me(principal);
	}

	private ResponseEntity<AuthResponse> withSession(AuthResponse auth) {
		// Keep token out of browser JS; session lives in HttpOnly cookie.
		AuthResponse body = new AuthResponse(null, auth.user());
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, sessionCookieService.create(auth.token()).toString())
				.body(body);
	}
}
