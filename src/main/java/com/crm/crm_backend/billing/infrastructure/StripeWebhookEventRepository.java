package com.crm.crm_backend.billing.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.billing.domain.StripeWebhookEvent;
import com.crm.crm_backend.billing.domain.WebhookProcessingStatus;

public interface StripeWebhookEventRepository extends JpaRepository<StripeWebhookEvent, UUID> {

	Optional<StripeWebhookEvent> findByStripeEventId(String stripeEventId);

	/** Events left in RECEIVED / PROCESSING / FAILED, for the retry job. */
	List<StripeWebhookEvent> findByProcessingStatusInAndUpdatedAtBefore(
			Collection<WebhookProcessingStatus> statuses, Instant before);
}
