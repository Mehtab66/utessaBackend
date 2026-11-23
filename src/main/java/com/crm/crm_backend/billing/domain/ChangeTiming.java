package com.crm.crm_backend.billing.domain;

/** When a subscription change takes effect. */
public enum ChangeTiming {
	/** Now, with prorations (charged now for upgrades, credited for downgrades). */
	IMMEDIATE,
	/** At the next renewal (end of the current billing period); nothing charged now. */
	PERIOD_END
}
