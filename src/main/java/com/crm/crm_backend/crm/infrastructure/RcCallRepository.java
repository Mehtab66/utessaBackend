package com.crm.crm_backend.crm.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.crm.domain.RcCall;

public interface RcCallRepository extends JpaRepository<RcCall, UUID> {
	boolean existsByOrganizationIdAndRcRecordId(UUID organizationId, String rcRecordId);

	boolean existsByOrganizationIdAndTelephonySessionId(UUID organizationId, String telephonySessionId);

	List<RcCall> findByOrganizationIdOrderByStartedAtDesc(UUID organizationId, Pageable page);

	List<RcCall> findByOrganizationIdAndMissedTrueOrderByStartedAtDesc(UUID organizationId, Pageable page);
}
