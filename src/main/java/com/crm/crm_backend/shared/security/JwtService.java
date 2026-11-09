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

	public JwtService(AppProperties appProperties) {
		this.appProperties = appProperties;
		this.key = Keys.hmacShaKeyFor(appProperties.jwt().secret().getBytes(StandardCharsets.UTF_8));
	}

	public String generateToken(UUID userId, UUID organizationId, String email, String role, boolean emailVerified) {
		Date now = new Date();
		Date expiry = new Date(now.getTime() + appProperties.jwt().expirationMs());
		return Jwts.builder()
				.subject(userId.toString())
				.claim("orgId", organizationId.toString())
				.claim("email", email)
				.claim("role", role)
				.claim("emailVerified", emailVerified)
				.issuedAt(now)
				.expiration(expiry)
				.signWith(key)
				.compact();
	}

	public UserPrincipal parse(String token) {
		Claims claims = Jwts.parser()
				.verifyWith(key)
				.build()
				.parseSignedClaims(token)
				.getPayload();

		return new UserPrincipal(
				UUID.fromString(claims.getSubject()),
				UUID.fromString(claims.get("orgId", String.class)),
				claims.get("email", String.class),
				claims.get("role", String.class),
				Boolean.TRUE.equals(claims.get("emailVerified", Boolean.class))
		);
	}
}
