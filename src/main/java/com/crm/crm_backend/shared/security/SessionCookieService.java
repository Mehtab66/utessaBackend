package com.crm.crm_backend.shared.security;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class SessionCookieService {

	public static final String COOKIE_NAME = "UTESSA_SESSION";

	private final AppProperties appProperties;

	public SessionCookieService(AppProperties appProperties) {
		this.appProperties = appProperties;
	}

	public ResponseCookie create(String token) {
		return base(token, appProperties.jwt().expirationMs() / 1000).build();
	}

	public ResponseCookie clear() {
		return base("", 0).build();
	}

	private ResponseCookie.ResponseCookieBuilder base(String value, long maxAgeSeconds) {
		return ResponseCookie.from(COOKIE_NAME, value)
				.httpOnly(true)
				.secure(false) // localhost; enable via reverse-proxy TLS in production
				.path("/")
				.sameSite("Lax")
				.maxAge(maxAgeSeconds);
	}
}
