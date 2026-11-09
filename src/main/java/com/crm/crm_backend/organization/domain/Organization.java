package com.crm.crm_backend.organization.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "organizations")
public class Organization {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false, unique = true)
	private String slug;

	@Column(name = "team_size_band")
	private Integer teamSizeBand;

	@Column(name = "org_type")
	private String orgType;

	@Column(name = "ehr_name")
	private String ehrName;

	@Column(name = "trial_starts_at", nullable = false)
	private Instant trialStartsAt;

	@Column(name = "trial_ends_at", nullable = false)
	private Instant trialEndsAt;

	@Column(name = "terms_accepted", nullable = false)
	private boolean termsAccepted;

	@Column(name = "news_opt_in", nullable = false)
	private boolean newsOptIn;

	@Column(name = "onboarding_done", nullable = false)
	private boolean onboardingDone;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "organization_services", joinColumns = @JoinColumn(name = "organization_id"))
	@Column(name = "service")
	private Set<String> enabledServices = new HashSet<>();

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onCreate() {
		if (id == null) {
			id = UUID.randomUUID();
		}
		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}
}
