package com.crm.crm_backend.crm.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.crm.crm_backend.crm.domain.RcConnection;

public interface RcConnectionRepository extends JpaRepository<RcConnection, UUID> {

	/** Updates only the sync timestamps so a concurrent token refresh is never overwritten. */
	@Modifying
	@Query("update RcConnection c set c.lastSyncAt = :calls, c.lastMessageSyncAt = :texts where c.id = :id")
	int updateSync(@Param("id") UUID id, @Param("calls") Instant calls, @Param("texts") Instant texts);

	/** Records a newly SMS-enabled number without touching the token columns. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update RcConnection c set c.smsNumber = :sms where c.id = :id")
	int updateSmsNumber(@Param("id") UUID id, @Param("sms") String sms);

	Optional<RcConnection> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

	List<RcConnection> findByOrganizationId(UUID organizationId);

	List<RcConnection> findByStatus(String status);
}
