package com.crm.crm_backend.timeclock.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.timeclock.domain.EntryAudit;

public interface EntryAuditRepository extends JpaRepository<EntryAudit, UUID> {

	List<EntryAudit> findByOrganizationIdAndEntryIdOrderByCreatedAtAsc(UUID organizationId, UUID entryId);
}
