package com.crm.crm_backend.timeclock.domain;

import java.time.Instant;
import java.util.UUID;

import com.crm.crm_backend.shared.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One change to a time entry: who, what, when and why. Never edited or deleted. */
@Getter
@Setter
@Entity
@Table(name = "tc_entry_audit")
public class EntryAudit extends BaseTenantEntity {

	@Id
	private UUID id;

	@Column(name = "entry_id", nullable = false)
	private UUID entryId;

	@Column(name = "subject_user_id", nullable = false)
	private UUID subjectUserId;

	@Column(name = "actor_user_id")
	private UUID actorUserId;

	@Column(name = "actor_name")
	private String actorName;

	@Column(nullable = false)
	private String action;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String summary;

	@Column(columnDefinition = "TEXT")
	private String reason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@PrePersist
	void onCreate() {
		assignTenant();
		if (id == null) {
			id = UUID.randomUUID();
		}
		createdAt = Instant.now();
	}
}
