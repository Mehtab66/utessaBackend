package com.crm.crm_backend.crm.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts third-party tokens at rest (AES-256-GCM) and signs short-lived values such as the
 * OAuth {@code state}. Both keys are derived from the server's JWT secret, so nothing new has
 * to be configured.
 */
@Component
public class TokenCipher {

	private static final SecureRandom RANDOM = new SecureRandom();
	private final SecretKeySpec aesKey;
	private final SecretKeySpec macKey;

	public TokenCipher(@Value("${app.jwt.secret}") String secret) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			byte[] enc = sha.digest(("utessa-token-enc:" + secret).getBytes(StandardCharsets.UTF_8));
			byte[] mac = sha.digest(("utessa-token-mac:" + secret).getBytes(StandardCharsets.UTF_8));
			this.aesKey = new SecretKeySpec(enc, "AES");
			this.macKey = new SecretKeySpec(mac, "HmacSHA256");
		} catch (Exception ex) {
			throw new IllegalStateException("Could not set up token encryption", ex);
		}
	}

	public String encrypt(String plain) {
		if (plain == null) {
			return null;
		}
		try {
			byte[] iv = new byte[12];
			RANDOM.nextBytes(iv);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
			byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			byte[] out = new byte[iv.length + ct.length];
			System.arraycopy(iv, 0, out, 0, iv.length);
			System.arraycopy(ct, 0, out, iv.length, ct.length);
			return Base64.getEncoder().encodeToString(out);
		} catch (Exception ex) {
			throw new IllegalStateException("Could not encrypt token", ex);
		}
	}

	public String decrypt(String stored) {
		if (stored == null || stored.isBlank()) {
			return null;
		}
		try {
			byte[] all = Base64.getDecoder().decode(stored);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, all, 0, 12));
			return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
		} catch (Exception ex) {
			return null;
		}
	}

	/** Returns {@code payload.signature}, URL-safe. */
	public String sign(String payload) {
		String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
		return body + "." + mac(body);
	}

	/** Returns the payload when the signature matches, otherwise null. */
	public String verify(String signed) {
		if (signed == null) {
			return null;
		}
		int dot = signed.lastIndexOf('.');
		if (dot <= 0) {
			return null;
		}
		String body = signed.substring(0, dot);
		String sig = signed.substring(dot + 1);
		if (!MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8), mac(body).getBytes(StandardCharsets.UTF_8))) {
			return null;
		}
		try {
			return new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private String mac(String body) {
		try {
			Mac m = Mac.getInstance("HmacSHA256");
			m.init(macKey);
			return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(body.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new IllegalStateException("Could not sign value", ex);
		}
	}
}
