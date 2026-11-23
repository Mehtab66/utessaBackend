package com.crm.crm_backend.billing.domain;

/** Lifecycle of a subscription line item. Scheduled states take effect at the next renewal. */
public enum SubscriptionItemStatus {
	ACTIVE,
	SCHEDULED_ADD,
	SCHEDULED_REMOVAL,
	REMOVED
}
