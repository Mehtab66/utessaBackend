package com.crm.crm_backend.billing.domain;

import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One line on the subscription: a module (quantity 1) or team-member seats (quantity = seats). */
@Getter
@Setter
@Entity
@Table(name = "subscription_items")
public class SubscriptionItem extends BillingAuditedEntity {

	/** {@link #moduleKey} of the team-member seat line. */
	public static final String SEAT_KEY = "seat";

	@Column(name = "subscription_id", nullable = false, updatable = false)
	private UUID subscriptionId;

	/** crm | ops | mkt | tc | seat */
	@Column(name = "module_key", nullable = false)
	private String moduleKey;

	@Column(name = "stripe_subscription_item_id")
	private String stripeSubscriptionItemId;

	@Column(name = "stripe_price_id", nullable = false)
	private String stripePriceId;

	@Column(nullable = false)
	private int quantity;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "billing_interval", nullable = false, columnDefinition = "billing_interval")
	private BillingInterval billingInterval;

	/** Price per unit in the currency's minor unit (e.g. cents). */
	@Column(name = "unit_amount", nullable = false)
	private long unitAmount;

	@Column(nullable = false, length = 3)
	private String currency;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false, columnDefinition = "billing_item_status")
	private SubscriptionItemStatus status = SubscriptionItemStatus.ACTIVE;

	public boolean isSeat() {
		return SEAT_KEY.equals(moduleKey);
	}
}
