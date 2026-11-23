package com.crm.crm_backend.billing.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.crm.crm_backend.billing.domain.StripeWebhookEvent;
import com.crm.crm_backend.billing.domain.WebhookProcessingStatus;
import com.crm.crm_backend.billing.infrastructure.StripeWebhookEventRepository;

/**
 * Re-processes webhook events that failed or were interrupted, by reading the event again from
 * Stripe (so nothing depends on keeping payloads). Stripe also retries on its own for 3 days;
 * this makes recovery independent of that.
 */
@Component
public class StripeWebhookRetryJob {

	private static final Logger log = LoggerFactory.getLogger(StripeWebhookRetryJob.class);
	private static final int MAX_ATTEMPTS = 10;

	private final StripeProperties props;
	private final StripeWebhookEventRepository events;
	private final StripeGateway gateway;
	private final StripeWebhookService webhooks;
	private final AtomicBoolean running = new AtomicBoolean(false);

	public StripeWebhookRetryJob(StripeProperties props, StripeWebhookEventRepository events, StripeGateway gateway,
			StripeWebhookService webhooks) {
		this.props = props;
		this.events = events;
		this.gateway = gateway;
		this.webhooks = webhooks;
	}

	@Scheduled(initialDelayString = "90000", fixedDelayString = "${billing.webhook-retry-interval-ms:300000}")
	public void run() {
		if (!props.configured() || !running.compareAndSet(false, true)) {
			return;
		}
		try {
			List<StripeWebhookEvent> stuck = events.findByProcessingStatusInAndUpdatedAtBefore(
					List.of(WebhookProcessingStatus.FAILED, WebhookProcessingStatus.RECEIVED, WebhookProcessingStatus.PROCESSING),
					Instant.now().minus(Duration.ofMinutes(3)));
			for (StripeWebhookEvent e : stuck) {
				if (e.getAttempts() >= MAX_ATTEMPTS) {
					continue;
				}
				try {
					webhooks.handle(gateway.retrieveEvent(e.getStripeEventId()));
				} catch (RuntimeException ex) {
					log.warn("Retry of Stripe event {} ({}) failed: {}", e.getStripeEventId(), e.getEventType(), ex.getMessage());
				}
			}
		} finally {
			running.set(false);
		}
	}
}
