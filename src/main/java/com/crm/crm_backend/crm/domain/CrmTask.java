package com.crm.crm_backend.crm.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Follow-up / work item: call back, verify insurance, request records, etc. */
@Getter
@Setter
@Entity
@Table(name = "crm_tasks")
public class CrmTask extends CrmAuditedEntity {

	@Column(name = "patient_id")
	private UUID patientId;

	@Column(name = "opportunity_id")
	private UUID opportunityId;

	@Column(name = "referral_id")
	private UUID referralId;

	@Column(nullable = false)
	private String title;

	@Column(name = "task_type", nullable = false)
	private String taskType = "CALL";

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "crm_priority")
	private Priority priority = Priority.MEDIUM;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "crm_task_status")
	private TaskStatus status = TaskStatus.OPEN;

	@Column(name = "due_at", nullable = false)
	private Instant dueAt;

	@Column(name = "assigned_user_id")
	private UUID assignedUserId;

	@Column(name = "assigned_name")
	private String assignedName;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "completed_by_name")
	private String completedByName;

	@Column(columnDefinition = "TEXT")
	private String notes;

	@Column(name = "created_by_name")
	private String createdByName;

	public boolean isOpen() {
		return status == TaskStatus.OPEN || status == TaskStatus.IN_PROGRESS;
	}
}
