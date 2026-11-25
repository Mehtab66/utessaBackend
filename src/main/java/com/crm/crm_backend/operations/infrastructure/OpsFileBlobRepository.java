package com.crm.crm_backend.operations.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.operations.domain.OpsFileBlob;

public interface OpsFileBlobRepository extends JpaRepository<OpsFileBlob, UUID> {
}
