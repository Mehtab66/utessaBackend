package com.crm.crm_backend.workspace.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.workspace.domain.Announcement;

public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {
	List<Announcement> findByOrganizationIdOrderByPinnedDescCreatedAtDesc(UUID organizationId);

	Optional<Announcement> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
