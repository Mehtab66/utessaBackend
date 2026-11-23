package com.crm.crm_backend.crm.application;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.crm.crm_backend.crm.domain.RcConnection;
import com.crm.crm_backend.crm.infrastructure.RcConnectionRepository;

/**
 * Pulls every connected staff member's RingCentral call log and inbound texts on a timer, so
 * missed calls get logged, a call-back task and the auto-text even when nobody has Utessa open.
 * (RingCentral webhooks need a public HTTPS address, which a local install doesn't have.)
 */
@Configuration
@EnableScheduling
public class RingCentralSyncJob {

	private static final Logger log = LoggerFactory.getLogger(RingCentralSyncJob.class);

	private final RingCentralConfig config;
	private final RcConnectionRepository connections;
	private final RingCentralService service;
	private final AtomicBoolean running = new AtomicBoolean(false);

	public RingCentralSyncJob(RingCentralConfig config, RcConnectionRepository connections, RingCentralService service) {
		this.config = config;
		this.connections = connections;
		this.service = service;
	}

	@Scheduled(initialDelayString = "20000", fixedDelayString = "${ringcentral.sync-interval-ms:45000}")
	public void run() {
		if (!config.isConfigured() || !running.compareAndSet(false, true)) {
			return;
		}
		try {
			for (RcConnection c : connections.findByStatus("CONNECTED")) {
				try {
					var result = service.sync(c.getId());
					if (result.newCalls() > 0 || result.newTexts() > 0) {
						log.info("RingCentral sync: {} new calls ({} missed, {} auto-texts), {} new texts",
								result.newCalls(), result.missedCalls(), result.autoTexts(), result.newTexts());
					}
				} catch (RuntimeException ex) {
					log.warn("RingCentral sync failed for connection {}: {}", c.getId(), ex.getMessage());
				}
			}
		} finally {
			running.set(false);
		}
	}
}
