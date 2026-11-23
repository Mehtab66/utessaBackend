package com.crm.crm_backend.billing.domain;

/** Billing operations that create or change Stripe resources. Each one runs idempotently. */
public enum OperationType {
	CREATE_CUSTOMER,
	UPDATE_CUSTOMER,
	CREATE_SUBSCRIPTION,
	UPGRADE,
	DOWNGRADE,
	CHANGE_SEATS,
	CHANGE_INTERVAL,
	CANCEL,
	REACTIVATE,
	PAYMENT,
	/** Undo a change booked for the next renewal (releases the subscription schedule). */
	CANCEL_SCHEDULED_CHANGE
}
