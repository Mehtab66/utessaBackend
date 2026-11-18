package com.crm.crm_backend.workspace.domain;

import java.time.Instant;
import java.util.UUID;

import com.crm.crm_backend.crm.domain.CrmAuditedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Shared practice calendar entry (meetings, huddles, trainings, time off, reminders). */
@Getter
@Setter
@Entity
@Table(name = "workspace_events")
public class WorkspaceEvent extends CrmAuditedEntity {

	@Column(nullable = false)
	private String title;

	@Column(columnDefinition = "TEXT")
	private String description;

	private String location;

	/** MEETING, HUDDLE, TRAINING, TIME_OFF, REMINDER, OTHER */
	@Column(nullable = false)
	private String category = "MEETING";

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false)
	private Instant endsAt;

	@Column(name = "all_day", nullable = false)
	private boolean allDay;

	@Column(name = "created_by_user_id")
	private UUID createdByUserId;

	@Column(name = "created_by_name")
	private String createdByName;
}
