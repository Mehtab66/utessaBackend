package com.crm.crm_backend.crm.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.crm.crm_backend.crm.application.RingCentralClient.RcApiException;
import com.crm.crm_backend.crm.domain.RcConnection;
import com.crm.crm_backend.crm.infrastructure.RcConnectionRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Hands out a valid RingCentral access token for a connection, refreshing it when needed.
 * RingCentral refresh tokens are single-use, so refreshes are serialised per connection and
 * always run in their own transaction against a freshly read row.
 */
@Component
public class RingCentralTokens {

	private final RcConnectionRepository connections;
	private final RingCentralClient client;
	private final TokenCipher cipher;
	private final TransactionTemplate newTx;
	private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

	public RingCentralTokens(
			RcConnectionRepository connections,
			RingCentralClient client,
			TokenCipher cipher,
			PlatformTransactionManager txManager
	) {
		this.connections = connections;
		this.client = client;
		this.cipher = cipher;
		this.newTx = new TransactionTemplate(txManager);
		this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** Stores the token payload from /restapi/oauth/token on the connection (not saved here). */
	public void apply(RcConnection c, JsonNode token) {
		Instant now = Instant.now();
		c.setAccessTokenEnc(cipher.encrypt(token.path("access_token").asText()));
		String refresh = token.path("refresh_token").asText(null);
		if (refresh != null && !refresh.isBlank()) {
			c.setRefreshTokenEnc(cipher.encrypt(refresh));
		}
		long expires = token.path("expires_in").asLong(3600);
		c.setAccessExpiresAt(now.plusSeconds(Math.max(60, expires - 60)));
		long refreshExpires = token.path("refresh_token_expires_in").asLong(0);
		if (refreshExpires > 0) {
			c.setRefreshExpiresAt(now.plusSeconds(refreshExpires));
		}
	}

	private record Outcome(String token, String error, boolean expired) {
	}

	/** A usable access token. {@code force} refreshes even if the current one looks valid (after a 401). */
	public String accessToken(UUID connectionId, boolean force) {
		Object lock = locks.computeIfAbsent(connectionId, k -> new Object());
		Outcome outcome;
		synchronized (lock) {
			outcome = newTx.execute(status -> refreshInTx(connectionId, force));
		}
		if (outcome == null || outcome.token() == null) {
			String message = outcome == null ? "RingCentral is not connected." : outcome.error();
			throw ApiException.conflict(message);
		}
		return outcome.token();
	}

	private Outcome refreshInTx(UUID connectionId, boolean force) {
		RcConnection c = connections.findById(connectionId).orElse(null);
		if (c == null) {
			return new Outcome(null, "RingCentral is not connected.", false);
		}
		if (!force && c.getAccessExpiresAt() != null && c.getAccessExpiresAt().isAfter(Instant.now().plusSeconds(30))) {
			String token = cipher.decrypt(c.getAccessTokenEnc());
			if (token != null) {
				return new Outcome(token, null, false);
			}
		}
		String refresh = cipher.decrypt(c.getRefreshTokenEnc());
		if (refresh == null) {
			return expire(c, "RingCentral sign-in expired. Connect RingCentral again.");
		}
		try {
			JsonNode token = client.token(Map.of("grant_type", "refresh_token", "refresh_token", refresh));
			apply(c, token);
			c.setStatus("CONNECTED");
			c.setLastError(null);
			connections.save(c);
			return new Outcome(token.path("access_token").asText(), null, false);
		} catch (RcApiException ex) {
			if (ex.status() == 400 || ex.status() == 401) {
				return expire(c, "RingCentral sign-in expired (" + ex.getMessage() + "). Connect RingCentral again.");
			}
			return new Outcome(null, "RingCentral could not refresh the sign-in: " + ex.getMessage(), false);
		}
	}

	private Outcome expire(RcConnection c, String message) {
		c.setStatus("ERROR");
		c.setLastError(CrmSupport.truncate(message, 500));
		connections.save(c);
		return new Outcome(null, message, true);
	}

	/** Calls the API with a valid token, retrying once with a forced refresh on 401. */
	public JsonNode call(UUID connectionId, String method, String path, Object body) {
		String token = accessToken(connectionId, false);
		try {
			return client.api(method, token, path, body);
		} catch (RcApiException ex) {
			if (ex.status() != 401) {
				throw ex;
			}
			return client.api(method, accessToken(connectionId, true), path, body);
		}
	}
}
