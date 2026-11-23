package com.crm.crm_backend.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.crm.crm_backend.billing.application.IdempotencyService;
import com.crm.crm_backend.billing.application.StripeCallException;
import com.crm.crm_backend.billing.domain.IdempotencyRecord;
import com.crm.crm_backend.billing.domain.OperationStatus;
import com.crm.crm_backend.billing.domain.OperationType;
import com.crm.crm_backend.billing.infrastructure.IdempotencyRecordRepository;
import com.crm.crm_backend.shared.exception.ApiException;

/** Database-backed idempotency under real concurrency (real PostgreSQL locks and constraints). */
class IdempotencyIntegrationTest extends BillingIntegrationTestBase {

	public record Reply(String value) {
	}

	@Autowired
	IdempotencyService idempotency;

	@Autowired
	IdempotencyRecordRepository records;

	UUID org;

	@BeforeEach
	void newPractice() {
		org = practice(0).getId();
	}

	private Reply run(String key, Object request, IdempotencyService.Work<Reply> work) {
		return idempotency.execute(org, null, key, OperationType.CHANGE_SEATS, request, Reply.class, work);
	}

	private static String key() {
		return "it-" + UUID.randomUUID();
	}

	@Test
	void sameKeySentManyTimesAtOnceRunsTheStripeWorkOnce() throws Exception {
		AtomicInteger stripeCalls = new AtomicInteger();
		String key = key();
		List<Object> outcomes = concurrently(8, i -> run(key, Map.of("seats", 5), ctx -> {
			stripeCalls.incrementAndGet();
			sleep(800);
			return new IdempotencyService.Result<>(new Reply("done"), "sub_x");
		}));

		assertThat(stripeCalls.get()).isEqualTo(1);
		assertThat(outcomes).allSatisfy(o -> assertThat(o).satisfiesAnyOf(
				r -> assertThat(r).isEqualTo(new Reply("done")),
				r -> assertThat(r).isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT))));
		assertThat(outcomes).contains(new Reply("done"));
	}

	@Test
	void differentKeysAtOnceAllowOnlyOneChangeForThePractice() throws Exception {
		AtomicInteger stripeCalls = new AtomicInteger();
		List<Object> outcomes = concurrently(5, i -> run(key(), Map.of("seats", 6), ctx -> {
			stripeCalls.incrementAndGet();
			sleep(1500);
			return new IdempotencyService.Result<>(new Reply("done"), "sub_x");
		}));

		assertThat(stripeCalls.get()).isEqualTo(1);
		assertThat(outcomes.stream().filter(o -> o instanceof Reply)).hasSize(1);
		assertThat(outcomes.stream().filter(o -> o instanceof ApiException e
				&& e.getMessage().contains("Another billing change is in progress"))).hasSize(4);
	}

	@Test
	void retryAfterSuccessReplaysWithoutCallingStripeAgain() {
		AtomicInteger stripeCalls = new AtomicInteger();
		String key = key();
		IdempotencyService.Work<Reply> work = ctx -> {
			stripeCalls.incrementAndGet();
			return new IdempotencyService.Result<>(new Reply("first"), "sub_x");
		};
		assertThat(run(key, Map.of("seats", 7), work)).isEqualTo(new Reply("first"));
		assertThat(run(key, Map.of("seats", 7), work)).isEqualTo(new Reply("first"));
		assertThat(stripeCalls.get()).isEqualTo(1);
	}

	@Test
	void sameKeyForADifferentRequestIsRefused() {
		String key = key();
		run(key, Map.of("seats", 7), ctx -> new IdempotencyService.Result<>(new Reply("a"), null));
		assertThatThrownBy(() -> run(key, Map.of("seats", 8), ctx -> new IdempotencyService.Result<>(new Reply("b"), null)))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
	}

	@Test
	void timeoutIsUnknownBlocksOtherChangesAndRetryResumesWithTheSameStripeKey() {
		String key = key();
		List<String> stripeKeys = new ArrayList<>();

		assertThatThrownBy(() -> run(key, Map.of("seats", 9), ctx -> {
			stripeKeys.add(ctx.stripeKey("update"));
			throw new StripeCallException(StripeCallException.Outcome.UNKNOWN, HttpStatus.SERVICE_UNAVAILABLE,
					"connection_error", "timeout", null, null);
		})).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
		assertThat(record(key).getProcessingStatus()).isEqualTo(OperationStatus.UNKNOWN);

		// Never assumed failed: a different change waits until this one is resolved.
		assertThatThrownBy(() -> run(key(), Map.of("seats", 3), ctx -> new IdempotencyService.Result<>(new Reply("x"), null)))
				.isInstanceOf(ApiException.class).hasMessageContaining("still being confirmed");

		// Retrying the same key resumes it, sending Stripe the very same idempotency key.
		Reply r = run(key, Map.of("seats", 9), ctx -> {
			stripeKeys.add(ctx.stripeKey("update"));
			return new IdempotencyService.Result<>(new Reply("resumed"), "sub_x");
		});
		assertThat(r).isEqualTo(new Reply("resumed"));
		assertThat(stripeKeys).hasSize(2);
		assertThat(stripeKeys.get(1)).isEqualTo(stripeKeys.get(0));
		IdempotencyRecord rec = record(key);
		assertThat(rec.getProcessingStatus()).isEqualTo(OperationStatus.SUCCEEDED);
		assertThat(rec.getAttempts()).isEqualTo(2);
	}

	@Test
	void definiteStripeRejectionIsFailedAndReplayedAsTheSameError() {
		String key = key();
		AtomicInteger stripeCalls = new AtomicInteger();
		IdempotencyService.Work<Reply> declined = ctx -> {
			stripeCalls.incrementAndGet();
			throw new StripeCallException(StripeCallException.Outcome.REJECTED, HttpStatus.PAYMENT_REQUIRED,
					"card_declined", "Your card was declined.", null, null);
		};
		assertThatThrownBy(() -> run(key, Map.of("seats", 4), declined))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED));
		assertThatThrownBy(() -> run(key, Map.of("seats", 4), declined))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED));
		assertThat(stripeCalls.get()).isEqualTo(1);
		assertThat(record(key).getProcessingStatus()).isEqualTo(OperationStatus.FAILED);
	}

	@Test
	void missingOrMalformedKeyIsRejected() {
		assertThatThrownBy(() -> run(null, Map.of(), ctx -> null)).hasMessageContaining("Idempotency-Key header is required");
		assertThatThrownBy(() -> run("short", Map.of(), ctx -> null)).hasMessageContaining("8–100 characters");
	}

	// ── helpers ─────────────────────────────────────────────────────────

	private IdempotencyRecord record(String key) {
		return records.findByOrganizationIdAndIdempotencyKey(org, key).orElseThrow();
	}

	private interface Call {
		Object run(int i) throws Exception;
	}

	private static List<Object> concurrently(int n, Call call) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(n);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Object>> futures = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int idx = i;
			futures.add(pool.submit(() -> {
				start.await();
				try {
					return call.run(idx);
				} catch (ApiException e) {
					return e;
				}
			}));
		}
		start.countDown();
		List<Object> out = new ArrayList<>();
		for (Future<Object> f : futures) {
			out.add(f.get(60, TimeUnit.SECONDS));
		}
		pool.shutdownNow();
		return out;
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
