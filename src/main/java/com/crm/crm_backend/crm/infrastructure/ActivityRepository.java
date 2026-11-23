package com.crm.crm_backend.crm.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.crm.domain.Activity;
import com.crm.crm_backend.crm.domain.ActivityType;

public interface ActivityRepository extends JpaRepository<Activity, UUID> {

	List<Activity> findByOrganizationIdAndPatientIdOrderByOccurredAtDesc(UUID organizationId, UUID patientId);

	List<Activity> findByOrganizationIdAndOpportunityIdOrderByOccurredAtDesc(UUID organizationId, UUID opportunityId);

	List<Activity> findByOrganizationIdAndReferralIdOrderByOccurredAtDesc(UUID organizationId, UUID referralId);

	List<Activity> findByOrganizationIdOrderByOccurredAtDesc(UUID organizationId, Pageable pageable);

	List<Activity> findByOrganizationIdAndActivityTypeAndOccurredAtBetween(
			UUID organizationId, ActivityType type, Instant from, Instant to);

	List<Activity> findByOrganizationIdAndActivityTypeInOrderByOccurredAtDesc(
			UUID organizationId, Collection<ActivityType> types, Pageable pageable);
}
