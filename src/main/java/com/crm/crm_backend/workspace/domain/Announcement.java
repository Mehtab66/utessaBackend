package com.crm.crm_backend.workspace.domain;

import java.util.UUID;

import com.crm.crm_backend.crm.domain.CrmAuditedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Team board post — policy updates, schedule changes, shout-outs. */
@Getter
@Setter
@Entity
@Table(name = "workspace_announcements")
public class Announcement extends CrmAuditedEntity {

	@Column(nullable = false)
	private String title;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String body;

	/** GENERAL, POLICY, SCHEDULE, CELEBRATION, URGENT */
	@Column(nullable = false)
	private String category = "GENERAL";

	@Column(nullable = false)
	private boolean pinned;

	@Column(name = "author_user_id")
	private UUID authorUserId;

	@Column(name = "author_name")
	private String authorName;
}
