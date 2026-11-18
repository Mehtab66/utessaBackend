package com.crm.crm_backend.workspace.domain;

import java.util.UUID;

import com.crm.crm_backend.crm.domain.CrmAuditedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Personal note; optionally shared with the whole practice. */
@Getter
@Setter
@Entity
@Table(name = "workspace_notes")
public class WorkspaceNote extends CrmAuditedEntity {

	@Column(nullable = false)
	private String title;

	@Column(columnDefinition = "TEXT")
	private String body;

	@Column(nullable = false)
	private String color = "yellow";

	@Column(nullable = false)
	private boolean pinned;

	@Column(nullable = false)
	private boolean shared;

	@Column(name = "owner_user_id", nullable = false)
	private UUID ownerUserId;

	@Column(name = "owner_name")
	private String ownerName;
}
