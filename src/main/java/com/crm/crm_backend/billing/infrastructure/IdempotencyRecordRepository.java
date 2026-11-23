package com.crm.crm_backend.billing.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.crm.crm_backend.billing.domain.IdempotencyRecord;
import com.crm.crm_backend.billing.domain.OperationStatus;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

	Optional<IdempotencyRecord> findByOrganizationIdAndIdempotencyKey(UUID organizationId, String idempotencyKey);

	/** Operations of this organization that are still running or have an unknown outcome. */
	List<IdempotencyRecord> findByOrganizationIdAndProcessingStatusIn(UUID organizationId, Collection<OperationStatus> statuses);

	/** UNKNOWN operations (and interrupted PROCESSING ones) for the reconciliation job. */
	List<IdempotencyRecord> findByProcessingStatusInAndUpdatedAtBefore(Collection<OperationStatus> statuses, Instant before);

	/**
	 * Transaction-scoped PostgreSQL advisory lock per organization, so billing operations of one
	 * organization run one at a time even across several application instances. Released at commit.
	 */
	@Query(value = "SELECT CAST(pg_advisory_xact_lock(hashtextextended(CAST(:orgId AS text), 7310)) AS text)", nativeQuery = true)
	String lockOrganization(@Param("orgId") UUID organizationId);
}
