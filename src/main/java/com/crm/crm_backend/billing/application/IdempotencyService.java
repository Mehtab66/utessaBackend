package com.crm.crm_backend.billing.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.crm.crm_backend.billing.domain.IdempotencyRecord;
import com.crm.crm_backend.billing.domain.OperationStatus;
import com.crm.crm_backend.billing.domain.OperationType;
import com.crm.crm_backend.billing.infrastructure.IdempotencyRecordRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Runs billing operations exactly once per idempotency key.
 *
 * <ol>
 * <li><b>Claim</b> (short DB transaction, per-organization advisory lock): look the key up.
 * Same key + same request → resume or replay; same key + different request → 422; another
 * operation of this organization still running → 409. Otherwise insert it as PROCESSING.</li>
 * <li><b>Work</b> (no DB transaction open): the Stripe calls, each with a key derived from the
 * operation's stored Stripe key, so a retry sends Stripe exactly the same keys.</li>
 * <li><b>Finish</b> (short DB transaction): SUCCEEDED with the saved response, FAILED when Stripe
 * definitely rejected it, or UNKNOWN when the outcome can't be known (timeout, crash after the
 * Stripe call). UNKNOWN is never treated as failure: retrying the same key resumes it safely.</li>
 * </ol>
 */
@Service
public class IdempotencyService {

	private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
	private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_\\-:.]{8,100}$");
	/** How long one attempt may hold an operation before it counts as interrupted. */
	static final Duration LEASE = Duration.ofMinutes(2);

	/** The Stripe calls of one operation. */
	@FunctionalInterface
	public interface Work<R> {
		Result<R> run(Context ctx);
	}

	/** What the work produced: the API response and the main Stripe object it created/changed. */
	public record Result<R>(R response, String reference) {
	}

	/** Gives each Stripe call inside the operation its own stable idempotency key. */
	public static final class Context {
		private final String baseKey;
		private final UUID organizationId;

		Context(String baseKey, UUID organizationId) {
			this.baseKey = baseKey;
			this.organizationId = organizationId;
		}

		/** Stripe idempotency key for one step, identical on every retry of this operation. */
		public String stripeKey(String step) {
			return baseKey + ":" + step;
		}

		public UUID organizationId() {
			return organizationId;
		}
	}

	private final IdempotencyRecordRepository records;
	private final TransactionTemplate tx;
	private final ObjectMapper json;
	private final ObjectMapper canonical;

	public IdempotencyService(IdempotencyRecordRepository records, PlatformTransactionManager txManager, ObjectMapper json) {
		this.records = records;
		this.tx = new TransactionTemplate(txManager);
		this.json = json;
		this.canonical = JsonMapper.builder()
				.findAndAddModules()
				.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
				.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
				.build();
	}

	/**
	 * Runs {@code work} once for this organization + client key.
	 *
	 * @param request the normalized request (collections sorted); its hash binds the key to it
	 */
	public <R> R execute(UUID organizationId, UUID actorUserId, String clientKey, OperationType type,
			Object request, Class<R> responseType, Work<R> work) {
		String key = requireKey(clientKey);
		String body = canonicalJson(request);
		String hash = hash(type, body);

		Claim claim = tx.execute(status -> claim(organizationId, actorUserId, key, type, hash, body));
		if (claim.replay() != null) {
			return replay(claim.replay(), responseType);
		}

		IdempotencyRecord record = claim.record();
		Context ctx = new Context(record.getStripeIdempotencyKey(), organizationId);
		Result<R> result;
		try {
			result = work.run(ctx);
		} catch (StripeCallException ex) {
			if (ex.outcome() == StripeCallException.Outcome.REJECTED) {
				finish(record.getId(), OperationStatus.FAILED, null, null, String.valueOf(ex.status().value()), ex.getMessage());
				throw new ApiException(ex.status(), ex.getMessage());
			}
			finish(record.getId(), OperationStatus.UNKNOWN, null, null, ex.code(), ex.getMessage());
			throw unknownOutcome(ex.outcome() == StripeCallException.Outcome.NOT_APPLIED ? ex.getMessage() : null);
		} catch (ApiException ex) {
			// Our own validation inside the work (raised before anything was changed in Stripe).
			finish(record.getId(), OperationStatus.FAILED, null, null, String.valueOf(ex.getStatus().value()), ex.getMessage());
			throw ex;
		} catch (RuntimeException ex) {
			// e.g. the database failed after Stripe succeeded: the change may exist in Stripe.
			log.error("Billing operation {} {} ended unexpectedly; marked UNKNOWN", type, record.getId(), ex);
			finish(record.getId(), OperationStatus.UNKNOWN, null, null, "internal_error", ex.getClass().getSimpleName());
			throw unknownOutcome(null);
		}

		String responseBody = serialize(result.response());
		finish(record.getId(), OperationStatus.SUCCEEDED, result.reference(), responseBody, null, null);
		return result.response();
	}

	/**
	 * If this key already finished (succeeded or failed) for the same request, returns / re-throws
	 * its saved result. Call it before validating against the current subscription: after a
	 * successful change the subscription already reflects it, and a retry must get the original
	 * answer, not "nothing to change".
	 */
	public <R> Optional<R> replayIfFinished(UUID organizationId, String clientKey, OperationType type, Object request, Class<R> responseType) {
		String key = requireKey(clientKey);
		IdempotencyRecord r = records.findByOrganizationIdAndIdempotencyKey(organizationId, key).orElse(null);
		if (r == null) {
			return Optional.empty();
		}
		if (r.getOperationType() != type || !r.getRequestHash().equals(hash(type, canonicalJson(request)))) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
					"This Idempotency-Key was already used for a different billing request. Use a new key for a new change.");
		}
		if (r.getProcessingStatus() == OperationStatus.SUCCEEDED || r.getProcessingStatus() == OperationStatus.FAILED) {
			return Optional.ofNullable(replay(r, responseType));
		}
		return Optional.empty();
	}

	/** Validates the client-supplied Idempotency-Key header. */
	public static String requireKey(String clientKey) {
		if (clientKey == null || clientKey.isBlank()) {
			throw ApiException.badRequest("An Idempotency-Key header is required for billing changes.");
		}
		String key = clientKey.trim();
		if (!KEY_FORMAT.matcher(key).matches()) {
			throw ApiException.badRequest("Idempotency-Key must be 8–100 characters: letters, digits, - _ : .");
		}
		return key;
	}

	// ── steps ───────────────────────────────────────────────────────────

	private record Claim(IdempotencyRecord record, IdempotencyRecord replay) {
	}

	private Claim claim(UUID orgId, UUID actorUserId, String key, OperationType type, String hash, String body) {
		records.lockOrganization(orgId);
		Instant now = Instant.now();
		IdempotencyRecord existing = records.findByOrganizationIdAndIdempotencyKey(orgId, key).orElse(null);
		if (existing != null) {
			if (existing.getOperationType() != type || !existing.getRequestHash().equals(hash)) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
						"This Idempotency-Key was already used for a different billing request. Use a new key for a new change.");
			}
			switch (existing.getProcessingStatus()) {
				case SUCCEEDED, FAILED -> {
					return new Claim(null, existing);
				}
				case PROCESSING, PENDING -> {
					if (existing.getLockedUntil() != null && existing.getLockedUntil().isAfter(now)) {
						throw ApiException.conflict("This billing change is still being processed. Try again in a moment.");
					}
				}
				case UNKNOWN -> {
					// resumed below with the same Stripe keys
				}
			}
			blockIfOtherInFlight(orgId, existing.getId(), now);
			existing.setProcessingStatus(OperationStatus.PROCESSING);
			existing.setAttempts(existing.getAttempts() + 1);
			existing.setLockedUntil(now.plus(LEASE));
			existing.setErrorCode(null);
			existing.setErrorMessage(null);
			log.info("Resuming billing operation {} {} (attempt {})", type, existing.getId(), existing.getAttempts());
			return new Claim(records.save(existing), null);
		}

		blockIfOtherInFlight(orgId, null, now);
		IdempotencyRecord r = new IdempotencyRecord();
		r.setOrganizationId(orgId);
		r.setIdempotencyKey(key);
		r.setOperationType(type);
		r.setRequestHash(hash);
		r.setRequestBody(body);
		r.setStripeIdempotencyKey("utessa-" + type.name().toLowerCase() + "-" + orgId + "-" + key);
		r.setProcessingStatus(OperationStatus.PROCESSING);
		r.setAttempts(1);
		r.setLockedUntil(now.plus(LEASE));
		r.setActorUserId(actorUserId);
		return new Claim(records.saveAndFlush(r), null);
	}

	/** Only one billing change per organization at a time; an unresolved one blocks new ones. */
	private void blockIfOtherInFlight(UUID orgId, UUID selfId, Instant now) {
		List<IdempotencyRecord> open = records.findByOrganizationIdAndProcessingStatusIn(orgId,
				List.of(OperationStatus.PENDING, OperationStatus.PROCESSING, OperationStatus.UNKNOWN));
		for (IdempotencyRecord o : open) {
			if (o.getId().equals(selfId)) {
				continue;
			}
			boolean running = o.getProcessingStatus() != OperationStatus.UNKNOWN
					&& o.getLockedUntil() != null && o.getLockedUntil().isAfter(now);
			if (running) {
				throw ApiException.conflict("Another billing change is in progress for this practice. Try again in a moment.");
			}
			// UNKNOWN, or an attempt interrupted mid-call (lease expired): its outcome isn't known yet.
			throw ApiException.conflict("A previous billing change (" + o.getOperationType().name().toLowerCase().replace('_', ' ')
					+ ") is still being confirmed with Stripe. Retry that change, or try again shortly.");
		}
	}

	/**
	 * Settles an operation whose outcome was unknown, after the recovery job checked Stripe.
	 * Only moves records that are still UNKNOWN or interrupted (never overwrites a finished one).
	 */
	public boolean settle(UUID recordId, OperationStatus status, String reference, Object response, String errorMessage) {
		if (status != OperationStatus.SUCCEEDED && status != OperationStatus.FAILED) {
			throw new IllegalArgumentException("Can only settle as SUCCEEDED or FAILED");
		}
		String body = status == OperationStatus.SUCCEEDED ? serialize(response) : null;
		Boolean done = tx.execute(s -> {
			IdempotencyRecord r = records.findById(recordId).orElse(null);
			if (r == null) {
				return false;
			}
			records.lockOrganization(r.getOrganizationId());
			r = records.findById(recordId).orElseThrow();
			boolean interrupted = (r.getProcessingStatus() == OperationStatus.PROCESSING || r.getProcessingStatus() == OperationStatus.PENDING)
					&& (r.getLockedUntil() == null || r.getLockedUntil().isBefore(Instant.now()));
			if (r.getProcessingStatus() != OperationStatus.UNKNOWN && !interrupted) {
				return false;
			}
			r.setProcessingStatus(status);
			r.setLockedUntil(null);
			r.setCompletedAt(Instant.now());
			if (reference != null) {
				r.setResponseReference(reference);
			}
			r.setResponseBody(body);
			r.setErrorCode(status == OperationStatus.FAILED ? "409" : null);
			r.setErrorMessage(errorMessage);
			records.save(r);
			return true;
		});
		return Boolean.TRUE.equals(done);
	}

	private void finish(UUID recordId, OperationStatus status, String reference, String body, String errorCode, String errorMessage) {
		try {
			tx.executeWithoutResult(s -> records.findById(recordId).ifPresent(r -> {
				r.setProcessingStatus(status);
				r.setLockedUntil(null);
				if (reference != null) {
					r.setResponseReference(reference);
				}
				r.setResponseBody(body);
				r.setErrorCode(errorCode);
				r.setErrorMessage(errorMessage);
				if (status == OperationStatus.SUCCEEDED || status == OperationStatus.FAILED) {
					r.setCompletedAt(Instant.now());
				}
				records.save(r);
			}));
		} catch (RuntimeException ex) {
			// The lease expires on its own; the next retry sees an interrupted operation and resumes it.
			log.error("Could not record billing operation {} as {}", recordId, status, ex);
		}
	}

	private <R> R replay(IdempotencyRecord r, Class<R> type) {
		if (r.getProcessingStatus() == OperationStatus.FAILED) {
			HttpStatus status = HttpStatus.BAD_REQUEST;
			try {
				status = HttpStatus.valueOf(Integer.parseInt(r.getErrorCode()));
			} catch (RuntimeException ignored) {
				// keep 400
			}
			throw new ApiException(status, r.getErrorMessage() == null ? "This billing change failed." : r.getErrorMessage());
		}
		if (r.getResponseBody() == null) {
			return null;
		}
		try {
			return json.readValue(r.getResponseBody(), type);
		} catch (Exception ex) {
			throw new IllegalStateException("Stored billing response could not be read", ex);
		}
	}

	private static ApiException unknownOutcome(String detail) {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, (detail == null ? "We couldn't confirm this change with Stripe yet." : detail)
				+ " Retry the same request — you won't be charged twice.");
	}

	private String serialize(Object response) {
		try {
			return response == null ? null : json.writeValueAsString(response);
		} catch (Exception ex) {
			throw new IllegalStateException("Billing response could not be saved", ex);
		}
	}

	private String canonicalJson(Object request) {
		try {
			return canonical.writeValueAsString(request);
		} catch (Exception ex) {
			throw new IllegalStateException("Could not serialize billing request", ex);
		}
	}

	private static String hash(OperationType type, String canonicalRequest) {
		try {
			byte[] bytes = (type.name() + "|" + canonicalRequest).getBytes(StandardCharsets.UTF_8);
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (Exception ex) {
			throw new IllegalStateException("Could not fingerprint billing request", ex);
		}
	}
}
