package com.crm.crm_backend.operations.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.crm.crm_backend.shared.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "staff_members")
public class StaffMember extends BaseTenantEntity {

	@Id
	private UUID id;

	@Column(name = "first_name", nullable = false, length = 100)
	private String firstName;

	@Column(name = "middle_name", length = 100)
	private String middleName;

	@Column(name = "last_name", nullable = false, length = 100)
	private String lastName;

	@Column(name = "preferred_name", length = 100)
	private String preferredName;

	@Column(name = "date_of_birth")
	private LocalDate dateOfBirth;

	private String languages;

	private String street;
	private String apt;
	private String city;
	private String state;
	private String zip;

	@Column(name = "home_phone")
	private String homePhone;

	@Column(name = "mobile_phone")
	private String mobilePhone;

	@Column(name = "work_phone")
	private String workPhone;

	/** @deprecated prefer mobilePhone / workPhone; kept for older rows */
	private String phone;

	@Column(name = "personal_email")
	private String personalEmail;

	private String email;

	@Column(name = "emergency_name")
	private String emergencyName;

	@Column(name = "emergency_relationship")
	private String emergencyRelationship;

	@Column(name = "emergency_phone")
	private String emergencyPhone;

	@Column(name = "job_title", nullable = false, length = 150)
	private String jobTitle;

	private String department;
	private String designation;

	@Column(name = "manager_name")
	private String managerName;

	@Column(nullable = false, length = 80)
	private String category;

	@Column(name = "is_provider", nullable = false)
	private boolean provider;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "employment_status")
	private EmploymentStatus status = EmploymentStatus.ONBOARDING;

	@Column(name = "employment_type")
	private String employmentType;

	private String fte;

	@Column(name = "hours_per_week")
	private String hoursPerWeek;

	private String shift;

	@Column(name = "work_arrangement")
	private String workArrangement;

	@Column(name = "pay_type")
	private String payType;

	@Column(name = "pay_rate")
	private String payRate;

	@Column(name = "pay_frequency")
	private String payFrequency;

	@Column(name = "eval_plan")
	private String evalPlan;

	@Column(name = "access_role")
	private String accessRole;

	@Column(name = "create_login", nullable = false)
	private boolean createLogin;

	private String specialty;
	private String subspecialty;

	@Column(name = "board_status")
	private String boardStatus;

	@Column(name = "board_name")
	private String boardName;

	@Column(name = "board_expires_on")
	private LocalDate boardExpiresOn;

	@Column(name = "population_focus")
	private String populationFocus;

	@Column(name = "supervisor_name")
	private String supervisorName;

	private String npi;

	@Column(name = "dea_number")
	private String deaNumber;

	@Column(name = "dea_expires_on")
	private LocalDate deaExpiresOn;

	@Column(name = "taxonomy_code")
	private String taxonomyCode;

	@Column(name = "caqh_id")
	private String caqhId;

	@Column(name = "license_type")
	private String licenseType;

	@Column(name = "license_number")
	private String licenseNumber;

	@Column(name = "license_expires_on")
	private LocalDate licenseExpiresOn;

	private String location;

	@Column(name = "start_date")
	private LocalDate startDate;

	@Column(name = "term_date")
	private LocalDate termDate;

	@Column(name = "last_day_worked")
	private LocalDate lastDayWorked;

	@Column(name = "separation_type")
	private String separationType;

	@Column(name = "rehire_eligibility")
	private String rehireEligibility;

	@Column(name = "separation_notes", columnDefinition = "TEXT")
	private String separationNotes;

	@Column(columnDefinition = "TEXT")
	private String notes;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "credentials_json", nullable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> credentialsJson = new ArrayList<>();

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onCreate() {
		assignTenant();
		if (id == null) {
			id = UUID.randomUUID();
		}
		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
		if (status == null) {
			status = EmploymentStatus.ONBOARDING;
		}
		if (credentialsJson == null) {
			credentialsJson = new ArrayList<>();
		}
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}
}
