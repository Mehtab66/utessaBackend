package com.crm.crm_backend.billing.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A received Stripe webhook event. The unique event id makes processing idempotent. */
@Getter
@Setter
@Entity
@Table(name = "stripe_webhook_events")
public class StripeWebhookEvent extends BillingAuditedEntity {

	@Column(name = "stripe_event_id", nullable = false, updatable = false)
	private String stripeEventId;

	@Column(name = "event_type", nullable = false)
	private String eventType;

	@Column(name = "stripe_object_id")
	private String stripeObjectId;

	@Column(name = "organization_id")
	private UUID organizationId;

	@Column(nullable = false)
	private boolean livemode;

	@Column(name = "stripe_created_at")
	private Instant stripeCreatedAt;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "processing_status", nullable = false, columnDefinition = "billing_webhook_status")
	private WebhookProcessingStatus processingStatus = WebhookProcessingStatus.RECEIVED;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "processed_at")
	private Instant processedAt;

	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;
}
