package com.crm.crm_backend.billing.application;

import org.springframework.http.HttpStatus;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;

/**
 * A failed Stripe call, classified by what we know about its effect. The distinction matters:
 * a timeout does NOT mean the change failed, so it must never be treated as a clean failure.
 */
public class StripeCallException extends RuntimeException {

	public enum Outcome {
		/** Stripe rejected the request; nothing changed (bad request, card declined, ...). */
		REJECTED,
		/** Stripe did not apply it but a retry with the same key may work (rate limit, key in use). */
		NOT_APPLIED,
		/** We don't know whether Stripe applied it (timeout, connection drop, 5xx). */
		UNKNOWN
	}

	private final Outcome outcome;
	private final HttpStatus status;
	private final String code;
	private final String requestId;

	public StripeCallException(Outcome outcome, HttpStatus status, String code, String message, String requestId, Throwable cause) {
		super(message, cause);
		this.outcome = outcome;
		this.status = status;
		this.code = code;
		this.requestId = requestId;
	}

	public Outcome outcome() {
		return outcome;
	}

	/** HTTP status to show the client when the outcome is {@link Outcome#REJECTED}. */
	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public String requestId() {
		return requestId;
	}

	static StripeCallException from(StripeException ex) {
		String code = ex.getCode();
		String requestId = ex.getRequestId();
		String stripeMessage = ex.getStripeError() != null && ex.getStripeError().getMessage() != null
				? ex.getStripeError().getMessage() : ex.getMessage();
		if (ex instanceof ApiConnectionException) {
			return new StripeCallException(Outcome.UNKNOWN, HttpStatus.SERVICE_UNAVAILABLE, "connection_error",
					"Couldn't reach Stripe.", requestId, ex);
		}
		if (ex instanceof RateLimitException) {
			return new StripeCallException(Outcome.NOT_APPLIED, HttpStatus.TOO_MANY_REQUESTS, code,
					"Stripe is busy right now. Please try again in a moment.", requestId, ex);
		}
		if (ex instanceof IdempotencyException) {
			if ("idempotency_key_in_use".equals(code)) {
				return new StripeCallException(Outcome.NOT_APPLIED, HttpStatus.CONFLICT, code,
						"This billing change is already being processed.", requestId, ex);
			}
			return new StripeCallException(Outcome.REJECTED, HttpStatus.CONFLICT, code,
					"This request conflicts with an earlier one. Start the change again.", requestId, ex);
		}
		if (ex instanceof CardException) {
			return new StripeCallException(Outcome.REJECTED, HttpStatus.PAYMENT_REQUIRED, code,
					stripeMessage == null ? "The card was declined." : stripeMessage, requestId, ex);
		}
		if (ex instanceof InvalidRequestException) {
			return new StripeCallException(Outcome.REJECTED, HttpStatus.BAD_REQUEST, code,
					stripeMessage == null ? "Stripe rejected the request." : stripeMessage, requestId, ex);
		}
		if (ex instanceof AuthenticationException || ex instanceof PermissionException) {
			return new StripeCallException(Outcome.REJECTED, HttpStatus.SERVICE_UNAVAILABLE, code,
					"Billing is not set up correctly on this server.", requestId, ex);
		}
		Integer http = ex.getStatusCode();
		if (http != null && http >= 400 && http < 500) {
			return new StripeCallException(Outcome.REJECTED, HttpStatus.BAD_REQUEST, code,
					stripeMessage == null ? "Stripe rejected the request." : stripeMessage, requestId, ex);
		}
		// 5xx and anything unexpected: Stripe may have applied the change.
		return new StripeCallException(Outcome.UNKNOWN, HttpStatus.SERVICE_UNAVAILABLE, code,
				"Stripe didn't confirm the result.", requestId, ex);
	}
}
