package com.crm.crm_backend.billing.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.crm.crm_backend.billing.domain.OrganizationSubscription;

import jakarta.persistence.LockModeType;

public interface OrganizationSubscriptionRepository extends JpaRepository<OrganizationSubscription, UUID> {

	Optional<OrganizationSubscription> findByOrganizationId(UUID organizationId);

	/** Row lock for updates that must not interleave (e.g. two webhooks for the same subscription). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from OrganizationSubscription s where s.organizationId = :orgId")
	Optional<OrganizationSubscription> lockByOrganizationId(@Param("orgId") UUID organizationId);

	Optional<OrganizationSubscription> findByStripeCustomerId(String stripeCustomerId);

	Optional<OrganizationSubscription> findByStripeSubscriptionId(String stripeSubscriptionId);

	/** Past-due subscriptions whose grace period has run out (access gets restricted). */
	List<OrganizationSubscription> findByGracePeriodEndsAtBefore(Instant now);
}
