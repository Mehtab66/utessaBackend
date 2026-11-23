package com.crm.crm_backend.billing.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.crm.crm_backend.crm.application.TokenCipher;
import com.crm.crm_backend.shared.exception.ApiException;

/**
 * Signed, short-lived proof that the customer saw an invoice preview. Confirming a change that
 * charges money requires the token, and the token pins:
 * <ul>
 * <li>the organization and the exact requested change,</li>
 * <li>the subscription state it was computed from (any change in between → preview again),</li>
 * <li>the proration date, so Stripe charges exactly the previewed amount.</li>
 * </ul>
 * Signed with the server key (HMAC), so it can't be forged or edited by the browser.
 */
@Component
public class PreviewTokens {

	static final Duration TTL = Duration.ofMinutes(15);
	private static final String VERSION = "bp1";

	public record Claims(UUID organizationId, String changeHash, String stateHash, long prorationDate, Instant expiresAt) {
	}

	private final TokenCipher cipher;

	public PreviewTokens(TokenCipher cipher) {
		this.cipher = cipher;
	}

	public String issue(UUID orgId, String changeHash, String stateHash, long prorationDate, Instant expiresAt) {
		return cipher.sign(String.join("|", VERSION, orgId.toString(), changeHash, stateHash,
				Long.toString(prorationDate), Long.toString(expiresAt.getEpochSecond())));
	}

	/** Verifies the token for this organization, change and current subscription state. */
	public Claims require(String token, UUID orgId, String changeHash, String stateHash) {
		if (token == null || token.isBlank()) {
			throw ApiException.badRequest("Preview the change first, then confirm it (previewToken is required).");
		}
		String payload = cipher.verify(token.trim());
		String[] p = payload == null ? new String[0] : payload.split("\\|");
		if (p.length != 6 || !VERSION.equals(p[0])) {
			throw ApiException.badRequest("This preview is not valid. Preview the change again.");
		}
		Claims c;
		try {
			c = new Claims(UUID.fromString(p[1]), p[2], p[3], Long.parseLong(p[4]), Instant.ofEpochSecond(Long.parseLong(p[5])));
		} catch (RuntimeException ex) {
			throw ApiException.badRequest("This preview is not valid. Preview the change again.");
		}
		if (!c.organizationId().equals(orgId) || !c.changeHash().equals(changeHash)) {
			throw ApiException.badRequest("This preview was for a different change. Preview the change again.");
		}
		if (c.expiresAt().isBefore(Instant.now())) {
			throw new ApiException(HttpStatus.CONFLICT, "This preview has expired. Preview the change again to see the current amount.");
		}
		if (!c.stateHash().equals(stateHash)) {
			throw new ApiException(HttpStatus.CONFLICT, "Your subscription changed since this preview. Preview the change again.");
		}
		return c;
	}

	public static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
