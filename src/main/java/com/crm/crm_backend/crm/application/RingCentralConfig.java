package com.crm.crm_backend.crm.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** RingCentral app settings read from the environment (.env). The client secret never leaves the server. */
@Component
public class RingCentralConfig {

	private final boolean enabled;
	private final String clientId;
	private final String clientSecret;
	private final String serverUrl;
	private final String redirectUri;
	private final String frontendUrl;
	private final String fromNumber;

	public RingCentralConfig(
			@Value("${ringcentral.enabled:false}") String enabled,
			@Value("${ringcentral.client-id:}") String clientId,
			@Value("${ringcentral.client-secret:}") String clientSecret,
			@Value("${ringcentral.server:production}") String server,
			@Value("${ringcentral.redirect-uri:}") String redirectUri,
			@Value("${ringcentral.frontend-url:}") String frontendUrl,
			@Value("${ringcentral.from-number:}") String fromNumber,
			@Value("${server.port:8080}") String port,
			@Value("${app.cors.allowed-origins:}") String corsOrigins
	) {
		this.enabled = "true".equalsIgnoreCase(trim(enabled)) || "1".equals(trim(enabled)) || "yes".equalsIgnoreCase(trim(enabled));
		this.clientId = trim(clientId);
		this.clientSecret = trim(clientSecret);
		String s = trim(server).toLowerCase();
		// RingCentral retired the separate sandbox host; every app now uses the production platform.
		this.serverUrl = s.startsWith("http") ? stripSlash(trim(server)) : "https://platform.ringcentral.com";
		this.redirectUri = trim(redirectUri).isEmpty()
				? "http://localhost:" + trim(port) + "/api/v1/crm/ringcentral/oauth/callback"
				: trim(redirectUri);
		String fe = trim(frontendUrl);
		if (fe.isEmpty()) {
			String first = trim(corsOrigins).split(",")[0].trim();
			fe = first.isEmpty() || first.contains("*") ? "http://localhost:5173" : first;
		}
		this.frontendUrl = stripSlash(fe);
		this.fromNumber = trim(fromNumber);
	}

	/** True when the switch is on and real (non-placeholder) credentials are present. */
	public boolean isConfigured() {
		return enabled && !clientId.isEmpty() && !clientSecret.isEmpty()
				&& !clientId.toLowerCase().startsWith("your_") && !clientId.toLowerCase().startsWith("dummy");
	}

	public boolean isEnabled() {
		return enabled;
	}

	public String clientId() {
		return clientId;
	}

	public String clientSecret() {
		return clientSecret;
	}

	public String serverUrl() {
		return serverUrl;
	}

	public String redirectUri() {
		return redirectUri;
	}

	public String frontendUrl() {
		return frontendUrl;
	}

	public String fromNumber() {
		return fromNumber;
	}

	private static String trim(String v) {
		return v == null ? "" : v.trim();
	}

	private static String stripSlash(String v) {
		return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
	}
}
