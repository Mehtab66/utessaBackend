package com.crm.crm_backend.billing.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.crm.crm_backend.billing.domain.IdempotencyRecord;

/**
 * Background recovery: every couple of minutes, checks billing operations whose outcome was
 * unknown against Stripe and settles them. Safe with several app instances: settling re-checks
 * the record under the organization's lock, so an operation is settled exactly once.
 */
@Component
public class BillingReconciliationJob {

	private static final Logger log = LoggerFactory.getLogger(BillingReconciliationJob.class);

	private final StripeProperties props;
	private final BillingReconciler reconciler;
	private final AtomicBoolean running = new AtomicBoolean(false);

	public BillingReconciliationJob(StripeProperties props, BillingReconciler reconciler) {
		this.props = props;
		this.reconciler = reconciler;
	}

	@Scheduled(initialDelayString = "60000", fixedDelayString = "${billing.reconcile-interval-ms:120000}")
	public void run() {
		if (!props.configured() || !running.compareAndSet(false, true)) {
			return;
		}
		try {
			List<IdempotencyRecord> open = reconciler.candidates();
			if (open.isEmpty()) {
				return;
			}
			Map<BillingReconciler.Result, Integer> counts = new EnumMap<>(BillingReconciler.Result.class);
			for (IdempotencyRecord r : open) {
				try {
					counts.merge(reconciler.reconcile(r), 1, Integer::sum);
				} catch (RuntimeException ex) {
					log.warn("Reconciling billing operation {} failed: {}", r.getId(), ex.getMessage());
				}
			}
			log.info("Billing recovery checked {} operation(s): {}", open.size(), counts);
		} finally {
			running.set(false);
		}
	}
}
