package com.crm.crm_backend.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.operations.domain.VendorInvoice;

public interface VendorInvoiceRepository extends JpaRepository<VendorInvoice, UUID> {

	List<VendorInvoice> findByOrganizationIdOrderByInvoiceDateDesc(UUID organizationId);

	List<VendorInvoice> findByOrganizationIdAndVendorIdOrderByInvoiceDateDesc(UUID organizationId, UUID vendorId);

	Optional<VendorInvoice> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
