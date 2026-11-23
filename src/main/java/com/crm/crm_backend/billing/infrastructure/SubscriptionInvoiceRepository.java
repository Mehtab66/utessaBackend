package com.crm.crm_backend.billing.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.billing.domain.SubscriptionInvoice;

public interface SubscriptionInvoiceRepository extends JpaRepository<SubscriptionInvoice, UUID> {

	Optional<SubscriptionInvoice> findByStripeInvoiceId(String stripeInvoiceId);

	List<SubscriptionInvoice> findByOrganizationIdOrderByStripeCreatedAtDesc(UUID organizationId, Pageable page);
}
