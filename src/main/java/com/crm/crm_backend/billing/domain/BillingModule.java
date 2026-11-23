package com.crm.crm_backend.billing.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The four paid modules. {@link #key()} matches the service keys already stored in
 * {@code organization_services} ("crm", "ops", "mkt", "tc"), so billing and the existing
 * module toggles speak the same language.
 */
public enum BillingModule {
	CRM("crm", "CRM"),
	OPERATIONS("ops", "Operations"),
	MARKETING("mkt", "Marketing"),
	TIME_CLOCK("tc", "Time Clock");

	private final String key;
	private final String label;

	BillingModule(String key, String label) {
		this.key = key;
		this.label = label;
	}

	public String key() {
		return key;
	}

	public String label() {
		return label;
	}

	public static Optional<BillingModule> fromKey(String key) {
		if (key == null) {
			return Optional.empty();
		}
		String k = key.trim().toLowerCase();
		return Arrays.stream(values())
				.filter(m -> m.key.equals(k) || m.name().equalsIgnoreCase(k))
				.findFirst();
	}
}
