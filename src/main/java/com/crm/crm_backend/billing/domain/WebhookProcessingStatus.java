package com.crm.crm_backend.billing.domain;

/** Processing state of a received Stripe webhook event. */
public enum WebhookProcessingStatus {
	RECEIVED,
	PROCESSING,
	PROCESSED,
	FAILED,
	IGNORED
}
