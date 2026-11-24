package com.crm.crm_backend.billing.api;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.crm.crm_backend.billing.application.StripeCallException;
import com.crm.crm_backend.shared.exception.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Stripe errors raised outside an idempotent operation (previews, reads) get their own status and
 * a readable message instead of a generic 500. Ordered first so it wins over the catch-all handler.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StripeErrorHandler {

	private static final Logger log = LoggerFactory.getLogger(StripeErrorHandler.class);

	@ExceptionHandler(StripeCallException.class)
	public ResponseEntity<ErrorResponse> handle(StripeCallException ex, HttpServletRequest request) {
		HttpStatus status = ex.outcome() == StripeCallException.Outcome.REJECTED ? ex.status() : HttpStatus.SERVICE_UNAVAILABLE;
		log.warn("Stripe call failed on {} ({}, code {}, request {}): {}", request.getRequestURI(), ex.outcome(), ex.code(),
				ex.requestId(), ex.getMessage());
		String message = ex.outcome() == StripeCallException.Outcome.REJECTED
				? "Stripe couldn't process this: " + ex.getMessage()
				: "Billing couldn't reach Stripe just now. Nothing was changed — please try again in a moment.";
		return ResponseEntity.status(status).body(new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(),
				message, request.getRequestURI()));
	}
}
