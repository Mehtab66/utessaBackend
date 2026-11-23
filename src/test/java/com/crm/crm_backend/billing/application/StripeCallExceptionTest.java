package com.crm.crm_backend.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.crm.crm_backend.billing.application.StripeCallException.Outcome;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.RateLimitException;

/** A timeout must never be treated as a clean failure; a declined card is a definite one. */
class StripeCallExceptionTest {

	@Test
	void connectionProblemsAreUnknownNotFailed() {
		assertThat(StripeCallException.from(new ApiConnectionException("timeout")).outcome()).isEqualTo(Outcome.UNKNOWN);
	}

	@Test
	void stripeServerErrorsAreUnknown() {
		assertThat(StripeCallException.from(new ApiException("boom", "req_1", null, 500, null)).outcome()).isEqualTo(Outcome.UNKNOWN);
	}

	@Test
	void declinedCardIsRejectedWithPaymentRequired() {
		StripeCallException e = StripeCallException.from(
				new CardException("Your card was declined.", "req_1", "card_declined", null, "generic_decline", null, 402, null));
		assertThat(e.outcome()).isEqualTo(Outcome.REJECTED);
		assertThat(e.status()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
	}

	@Test
	void invalidRequestIsRejected() {
		assertThat(StripeCallException.from(new InvalidRequestException("bad", null, "req_1", "resource_missing", 404, null)).outcome())
				.isEqualTo(Outcome.REJECTED);
	}

	@Test
	void rateLimitAndKeyInUseAreSafeToRetry() {
		assertThat(StripeCallException.from(new RateLimitException("slow down", null, "req_1", "rate_limit", 429, null)).outcome())
				.isEqualTo(Outcome.NOT_APPLIED);
		assertThat(StripeCallException.from(new IdempotencyException("in use", "req_1", "idempotency_key_in_use", 409)).outcome())
				.isEqualTo(Outcome.NOT_APPLIED);
	}
}
