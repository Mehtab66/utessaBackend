package com.crm.crm_backend.billing.application;

import javax.net.ssl.HttpsURLConnection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.crm.crm_backend.shared.net.SystemTrust;
import com.stripe.StripeClient;

/**
 * Builds the single {@link StripeClient} the app uses. The secret key is passed only here and is
 * never logged or returned to the frontend.
 */
@Configuration
@EnableConfigurationProperties(StripeProperties.class)
public class StripeConfig {

	private static final Logger log = LoggerFactory.getLogger(StripeConfig.class);
	/** Placeholder so the bean always exists; services check {@link StripeProperties#configured()} first. */
	private static final String NOT_CONFIGURED = "sk_not_configured";

	@Bean
	StripeClient stripeClient(StripeProperties props) {
		// The Stripe SDK uses HttpsURLConnection. Trust the JDK roots plus the OS store so HTTPS
		// scanning antivirus / corporate proxies don't break calls (same approach as RingCentral).
		HttpsURLConnection.setDefaultSSLSocketFactory(SystemTrust.sslContext().getSocketFactory());

		if (!props.configured()) {
			log.warn("Stripe billing is not configured (STRIPE_SECRET_KEY is empty); billing endpoints will be unavailable.");
		} else {
			log.info("Stripe billing configured ({} mode, currency {}).",
					props.secretKey().startsWith("sk_live_") ? "LIVE" : "test", props.currency());
		}
		return StripeClient.builder()
				.setApiKey(props.configured() ? props.secretKey() : NOT_CONFIGURED)
				.setConnectTimeout(props.connectTimeoutMs())
				.setReadTimeout(props.readTimeoutMs())
				// Network retries are safe: every mutating call carries our own idempotency key.
				.setMaxNetworkRetries(2)
				.build();
	}
}
