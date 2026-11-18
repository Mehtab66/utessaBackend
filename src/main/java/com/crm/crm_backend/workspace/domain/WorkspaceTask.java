package com.crm.crm_backend.workspace.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.crm.crm_backend.crm.domain.CrmAuditedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Module-independent work item ("My work"): personal or assigned to a teammate. */
@Getter
@Setter
@Entity
@Table(name = "workspace_tasks")
public class WorkspaceTask extends CrmAuditedEntity {

	@Column(nullable = false)
	private String title;

	@Column(columnDefinition = "TEXT")
	private String notes;

	/** TODO, IN_PROGRESS, DONE */
	@Column(nullable = false)
	private String status = "TODO";

	/** LOW, MEDIUM, HIGH, URGENT */
	@Column(nullable = false)
	private String priority = "MEDIUM";

	@Column(name = "due_date")
	private LocalDate dueDate;

	@Column(name = "assignee_user_id")
	private UUID assigneeUserId;

	@Column(name = "assignee_name")
	private String assigneeName;

	@Column(name = "created_by_user_id")
	private UUID createdByUserId;

	@Column(name = "created_by_name")
	private String createdByName;

	@Column(name = "completed_at")
	private Instant completedAt;
}
