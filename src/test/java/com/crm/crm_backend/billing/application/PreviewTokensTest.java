package com.crm.crm_backend.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.crm.crm_backend.crm.application.TokenCipher;
import com.crm.crm_backend.shared.exception.ApiException;

class PreviewTokensTest {

	private final PreviewTokens tokens = new PreviewTokens(new TokenCipher("unit-test-secret-0123456789abcdef0123456789abcdef"));
	private final UUID org = UUID.randomUUID();
	private final Instant later = Instant.now().plusSeconds(600);

	@Test
	void validTokenReturnsTheProrationDate() {
		String t = tokens.issue(org, "change", "state", 1_700_000_000L, later);
		assertThat(tokens.require(t, org, "change", "state").prorationDate()).isEqualTo(1_700_000_000L);
	}

	@Test
	void missingTokenIsRejected() {
		assertThatThrownBy(() -> tokens.require(null, org, "change", "state"))
				.isInstanceOf(ApiException.class).hasMessageContaining("Preview the change first");
	}

	@Test
	void editedTokenIsRejected() {
		String t = tokens.issue(org, "change", "state", 1L, later);
		assertThatThrownBy(() -> tokens.require(t + "x", org, "change", "state"))
				.isInstanceOf(ApiException.class).hasMessageContaining("not valid");
	}

	@Test
	void tokenOfAnotherOrganizationOrChangeIsRejected() {
		String t = tokens.issue(org, "change", "state", 1L, later);
		assertThatThrownBy(() -> tokens.require(t, UUID.randomUUID(), "change", "state"))
				.isInstanceOf(ApiException.class).hasMessageContaining("different change");
		assertThatThrownBy(() -> tokens.require(t, org, "other-change", "state"))
				.isInstanceOf(ApiException.class).hasMessageContaining("different change");
	}

	@Test
	void expiredTokenIsRejected() {
		String t = tokens.issue(org, "change", "state", 1L, Instant.now().minusSeconds(1));
		assertThatThrownBy(() -> tokens.require(t, org, "change", "state"))
				.isInstanceOf(ApiException.class).hasMessageContaining("expired");
	}

	@Test
	void tokenIsInvalidOnceTheSubscriptionChanged() {
		String t = tokens.issue(org, "change", "state-before", 1L, later);
		assertThatThrownBy(() -> tokens.require(t, org, "change", "state-after"))
				.isInstanceOf(ApiException.class).hasMessageContaining("changed since this preview");
	}
}
