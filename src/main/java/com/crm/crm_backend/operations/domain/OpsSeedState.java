package com.crm.crm_backend.operations.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Records which Practice Operations defaults were created for an organization. */
@Getter
@Setter
@Entity
@Table(name = "ops_seed_state")
public class OpsSeedState {

	@Id
	@Column(name = "organization_id")
	private UUID organizationId;

	private boolean payers;
	private boolean trackers;
	private boolean specialties;
	private boolean resources;
	private boolean training;
}
