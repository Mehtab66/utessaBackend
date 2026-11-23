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

/**
 * One logical billing operation. The client's Idempotency-Key is unique per organization; the
 * request hash stops a key from being reused for a different request.
 */
@Getter
@Setter
@Entity
@Table(name = "stripe_idempotency_records")
public class IdempotencyRecord extends BillingAuditedEntity {

	@Column(name = "idempotency_key", nullable = false, updatable = false)
	private String idempotencyKey;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private UUID organizationId;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "operation_type", nullable = false, updatable = false, columnDefinition = "billing_operation_type")
	private OperationType operationType;

	@Column(name = "request_hash", nullable = false, updatable = false)
	private String requestHash;

	/** Normalized request (canonical JSON) — lets the recovery job verify an unknown outcome in Stripe. */
	@Column(name = "request_body", columnDefinition = "TEXT", updatable = false)
	private String requestBody;

	/** Key sent to Stripe in RequestOptions; reused unchanged on every retry of this operation. */
	@Column(name = "stripe_idempotency_key", nullable = false, updatable = false)
	private String stripeIdempotencyKey;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "processing_status", nullable = false, columnDefinition = "billing_operation_status")
	private OperationStatus processingStatus = OperationStatus.PENDING;

	@Column(name = "response_reference")
	private String responseReference;

	@Column(name = "response_body", columnDefinition = "TEXT")
	private String responseBody;

	@Column(name = "error_code")
	private String errorCode;

	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "actor_user_id")
	private UUID actorUserId;

	@Column(name = "completed_at")
	private Instant completedAt;
}
