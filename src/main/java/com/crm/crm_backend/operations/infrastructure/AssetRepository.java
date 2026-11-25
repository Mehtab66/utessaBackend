package com.crm.crm_backend.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.operations.domain.Asset;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

	List<Asset> findByOrganizationIdOrderByAssetTypeAscNameAsc(UUID organizationId);

	Optional<Asset> findByIdAndOrganizationId(UUID id, UUID organizationId);

	long countByOrganizationId(UUID organizationId);
}
