package com.crm.crm_backend.billing.domain;

/**
 * PENDING → PROCESSING → SUCCEEDED / FAILED / UNKNOWN.
 * UNKNOWN means Stripe may or may not have applied the change (e.g. a timeout); it is resolved
 * later by retrying with the same idempotency key or reading the object back from Stripe.
 */
public enum OperationStatus {
	PENDING,
	PROCESSING,
	SUCCEEDED,
	FAILED,
	UNKNOWN
}
