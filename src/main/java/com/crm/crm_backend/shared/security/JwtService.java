package com.crm.crm_backend.shared.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

	private final AppProperties appProperties;
	private final SecretKey key;

	/** HMAC-SHA256 needs a key of at least 256 bits. */
	private static final int MIN_SECRET_BYTES = 32;

	public JwtService(AppProperties appProperties) {
		this.appProperties = appProperties;
		String secret = appProperties.jwt() == null ? null : appProperties.jwt().secret();
		if (secret == null || secret.isBlank() || secret.startsWith("${")) {
			throw new IllegalStateException(
					"JWT_SECRET is not set. Add JWT_SECRET=<64+ random hex characters> to utessaBackend/.env "
							+ "(see .env.example). Generate one with: python -c \"import secrets; print(secrets.token_hex(64))\"");
		}
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < MIN_SECRET_BYTES) {
			throw new IllegalStateException(
					"JWT_SECRET is too short (" + bytes.length + " bytes). It must be at least " + MIN_SECRET_BYTES
							+ " bytes — use 64+ random hex characters in utessaBackend/.env.");
		}
		this.key = Keys.hmacShaKeyFor(bytes);
	}

	public String generateToken(
			UUID userId,
			UUID organizationId,
			String email,
			String role,
			boolean emailVerified,
			boolean superAdmin
	) {
		Date now = new Date();
		Date expiry = new Date(now.getTime() + appProperties.jwt().expirationMs());
		var builder = Jwts.builder()
				.subject(userId.toString())
				.claim("email", email)
				.claim("emailVerified", emailVerified)
				.claim("superAdmin", superAdmin)
				.issuedAt(now)
				.expiration(expiry)
				.signWith(key);

		if (organizationId != null) {
			builder.claim("orgId", organizationId.toString());
		}
		if (role != null && !role.isBlank()) {
			builder.claim("role", role);
		}

		return builder.compact();
	}

	public UserPrincipal parse(String token) {
		Claims claims = Jwts.parser()
				.verifyWith(key)
				.build()
				.parseSignedClaims(token)
				.getPayload();

		String orgRaw = claims.get("orgId", String.class);
		UUID organizationId = (orgRaw == null || orgRaw.isBlank()) ? null : UUID.fromString(orgRaw);
		boolean superAdmin = Boolean.TRUE.equals(claims.get("superAdmin", Boolean.class));
		String role = claims.get("role", String.class);
		if (superAdmin && (role == null || role.isBlank())) {
			role = "SUPERADMIN";
		}

		return new UserPrincipal(
				UUID.fromString(claims.getSubject()),
				organizationId,
				claims.get("email", String.class),
				role,
				Boolean.TRUE.equals(claims.get("emailVerified", Boolean.class)),
				superAdmin
		);
	}
}
