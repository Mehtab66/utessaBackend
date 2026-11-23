package com.crm.crm_backend.billing.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.billing.domain.SubscriptionItem;
import com.crm.crm_backend.billing.domain.SubscriptionItemStatus;

public interface SubscriptionItemRepository extends JpaRepository<SubscriptionItem, UUID> {

	List<SubscriptionItem> findBySubscriptionId(UUID subscriptionId);

	List<SubscriptionItem> findBySubscriptionIdAndStatusIn(UUID subscriptionId, Collection<SubscriptionItemStatus> statuses);

	Optional<SubscriptionItem> findByStripeSubscriptionItemId(String stripeSubscriptionItemId);
}
