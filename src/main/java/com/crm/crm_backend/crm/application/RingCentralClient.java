package com.crm.crm_backend.crm.application;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

/** Thin HTTP wrapper over the RingCentral REST API (https://developers.ringcentral.com/api-reference). */
@Component
public class RingCentralClient {

	private final RingCentralConfig config;
	private final ObjectMapper json = new ObjectMapper();
	// Trusts Java's CAs plus the OS store, so antivirus HTTPS scanning (e.g. Avast) doesn't break the connection.
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.sslContext(SystemTrust.sslContext())
			.build();

	public RingCentralClient(RingCentralConfig config) {
		this.config = config;
	}

	/** Raised for any non-2xx answer from RingCentral. */
	public static class RcApiException extends RuntimeException {
		private final int status;
		private final String errorCode;
		private final JsonNode body;

		public RcApiException(int status, String message, String errorCode, JsonNode body) {
			super(message);
			this.status = status;
			this.errorCode = errorCode;
			this.body = body;
		}

		public int status() {
			return status;
		}

		public String errorCode() {
			return errorCode;
		}

		public JsonNode body() {
			return body;
		}
	}

	public String authorizeUrl(String state) {
		return config.serverUrl() + "/restapi/oauth/authorize?response_type=code"
				+ "&client_id=" + enc(config.clientId())
				+ "&redirect_uri=" + enc(config.redirectUri())
				+ "&state=" + enc(state)
				+ "&display=popup";
	}

	/** POST /restapi/oauth/token (authorization_code or refresh_token grant). */
	public JsonNode token(Map<String, String> form) {
		String body = form.entrySet().stream()
				.map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
				.collect(Collectors.joining("&"));
		String basic = Base64.getEncoder().encodeToString(
				(config.clientId() + ":" + config.clientSecret()).getBytes(StandardCharsets.UTF_8));
		HttpRequest req = HttpRequest.newBuilder(URI.create(config.serverUrl() + "/restapi/oauth/token"))
				.timeout(Duration.ofSeconds(20))
				.header("Authorization", "Basic " + basic)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		return send(req);
	}

	/** POST /restapi/oauth/revoke — ends the sign-in at RingCentral. Failures are ignored. */
	public void revoke(String token) {
		if (token == null) {
			return;
		}
		try {
			String basic = Base64.getEncoder().encodeToString(
					(config.clientId() + ":" + config.clientSecret()).getBytes(StandardCharsets.UTF_8));
			HttpRequest req = HttpRequest.newBuilder(URI.create(config.serverUrl() + "/restapi/oauth/revoke"))
					.timeout(Duration.ofSeconds(15))
					.header("Authorization", "Basic " + basic)
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString("token=" + enc(token)))
					.build();
			http.send(req, HttpResponse.BodyHandlers.discarding());
		} catch (Exception ignored) {
			// Revocation is best effort; the local tokens are deleted either way.
		}
	}

	public JsonNode get(String accessToken, String path) {
		return api("GET", accessToken, path, null);
	}

	public JsonNode post(String accessToken, String path, Object body) {
		return api("POST", accessToken, path, body);
	}

	public JsonNode api(String method, String accessToken, String path, Object body) {
		try {
			HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.serverUrl() + path))
					.timeout(Duration.ofSeconds(25))
					.header("Authorization", "Bearer " + accessToken)
					.header("Accept", "application/json");
			if (body != null) {
				b.header("Content-Type", "application/json")
						.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
			} else {
				b.method(method, "POST".equals(method)
						? HttpRequest.BodyPublishers.ofString("")
						: HttpRequest.BodyPublishers.noBody());
			}
			return send(b.build());
		} catch (RcApiException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new RcApiException(0, "RingCentral could not be reached: " + ex.getMessage(), null, MissingNode.getInstance());
		}
	}

	private JsonNode send(HttpRequest req) {
		HttpResponse<String> res;
		try {
			res = http.send(req, HttpResponse.BodyHandlers.ofString());
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new RcApiException(0, "RingCentral request was interrupted.", null, MissingNode.getInstance());
		} catch (Exception ex) {
			throw new RcApiException(0, "RingCentral could not be reached: " + ex.getMessage(), null, MissingNode.getInstance());
		}
		JsonNode node;
		try {
			node = res.body() == null || res.body().isBlank() ? MissingNode.getInstance() : json.readTree(res.body());
		} catch (Exception ex) {
			node = MissingNode.getInstance();
		}
		if (res.statusCode() >= 400) {
			String message = firstText(node, "error_description", "message", "error");
			String code = firstText(node, "errorCode", "error");
			if (node.has("errors") && node.get("errors").isArray() && node.get("errors").size() > 0) {
				JsonNode e = node.get("errors").get(0);
				if (message == null) {
					message = e.path("message").asText(null);
				}
				if (code == null) {
					code = e.path("errorCode").asText(null);
				}
			}
			throw new RcApiException(res.statusCode(),
					message == null ? "RingCentral returned an error (" + res.statusCode() + ")." : message, code, node);
		}
		return node;
	}

	private static String firstText(JsonNode node, String... keys) {
		for (String k : keys) {
			JsonNode v = node.path(k);
			if (v.isTextual() && !v.asText().isBlank()) {
				return v.asText();
			}
		}
		return null;
	}

	public static String enc(String v) {
		return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
	}
}
