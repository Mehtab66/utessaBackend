package com.crm.crm_backend.billing.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionSchedule;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerSearchParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.InvoiceCreatePreviewParams;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.SubscriptionRetrieveParams;
import com.stripe.param.SubscriptionScheduleCreateParams;
import com.stripe.param.SubscriptionScheduleUpdateParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionRetrieveParams;

/**
 * The only class that calls Stripe. Every call that creates or changes something carries an
 * idempotency key, and every failure is turned into a classified {@link StripeCallException}.
 * Logs carry operation names, Stripe IDs and request IDs only — never keys or card data.
 */
@Component
public class StripeGateway {

	private static final Logger log = LoggerFactory.getLogger(StripeGateway.class);

	/** Metadata key linking Stripe objects back to the organization. */
	public static final String ORG_METADATA = "organization_id";

	private final StripeClient stripe;
	private final StripeProperties props;

	public StripeGateway(StripeClient stripe, StripeProperties props) {
		this.stripe = stripe;
		this.props = props;
	}

	public void requireConfigured() {
		if (!props.configured()) {
			throw new StripeCallException(StripeCallException.Outcome.REJECTED, HttpStatus.SERVICE_UNAVAILABLE,
					"not_configured", "Billing is not set up on this server yet.", null, null);
		}
	}

	// ── customers ──────────────────────────────────────────────────────

	public Customer createCustomer(CustomerCreateParams params, String idempotencyKey) {
		return call("customer.create", () -> stripe.v1().customers().create(params, options(idempotencyKey)));
	}

	public Customer updateCustomer(String customerId, CustomerUpdateParams params, String idempotencyKey) {
		return call("customer.update", () -> stripe.v1().customers().update(customerId, params, options(idempotencyKey)));
	}

	public Customer retrieveCustomer(String customerId) {
		return call("customer.retrieve", () -> stripe.v1().customers().retrieve(customerId));
	}

	/**
	 * Finds a customer already created for this organization (by metadata). Covers the case where
	 * Stripe created the customer but our database write was lost. Search is eventually
	 * consistent, so it complements — never replaces — idempotency keys.
	 */
	public Optional<Customer> findCustomerForOrganization(UUID organizationId) {
		CustomerSearchParams params = CustomerSearchParams.builder()
				.setQuery("metadata['" + ORG_METADATA + "']:'" + organizationId + "'")
				.setLimit(10L)
				.build();
		List<Customer> found = call("customer.search", () -> stripe.v1().customers().search(params, RequestOptions.getDefault()).getData());
		return found.stream()
				.filter(c -> !Boolean.TRUE.equals(c.getDeleted()))
				.min((a, b) -> Long.compare(a.getCreated(), b.getCreated()));
	}

	// ── checkout ───────────────────────────────────────────────────────

	public Session createCheckoutSession(SessionCreateParams params, String idempotencyKey) {
		return call("checkout.create", () -> stripe.v1().checkout().sessions().create(params, options(idempotencyKey)));
	}

	public Session retrieveCheckoutSession(String sessionId) {
		SessionRetrieveParams params = SessionRetrieveParams.builder().build();
		return call("checkout.retrieve", () -> stripe.v1().checkout().sessions().retrieve(sessionId, params, RequestOptions.getDefault()));
	}

	/** Recent Checkout sessions of a customer (newest first). */
	public List<Session> listCheckoutSessions(String customerId) {
		com.stripe.param.checkout.SessionListParams params = com.stripe.param.checkout.SessionListParams.builder()
				.setCustomer(customerId)
				.setLimit(20L)
				.build();
		return call("checkout.list", () -> stripe.v1().checkout().sessions().list(params, RequestOptions.getDefault()).getData());
	}

	public Session expireCheckoutSession(String sessionId, String idempotencyKey) {
		return call("checkout.expire", () -> stripe.v1().checkout().sessions().expire(sessionId, options(idempotencyKey)));
	}

	// ── subscriptions ──────────────────────────────────────────────────

	public Subscription retrieveSubscription(String subscriptionId) {
		SubscriptionRetrieveParams params = SubscriptionRetrieveParams.builder()
				.addExpand("items.data.price")
				.build();
		return call("subscription.retrieve", () -> stripe.v1().subscriptions().retrieve(subscriptionId, params, RequestOptions.getDefault()));
	}

	/** Cancels a subscription right away (used for an accidental second subscription). */
	public Subscription cancelSubscriptionNow(String subscriptionId, String idempotencyKey) {
		com.stripe.param.SubscriptionCancelParams params = com.stripe.param.SubscriptionCancelParams.builder()
				.setInvoiceNow(false)
				.setProrate(false)
				.build();
		return call("subscription.cancel", () -> stripe.v1().subscriptions().cancel(subscriptionId, params, options(idempotencyKey)));
	}

	/** Re-reads an event from Stripe (webhook retry job). */
	public com.stripe.model.Event retrieveEvent(String eventId) {
		return call("event.retrieve", () -> stripe.v1().events().retrieve(eventId));
	}

	public Subscription updateSubscription(String subscriptionId, SubscriptionUpdateParams params, String idempotencyKey) {
		return call("subscription.update", () -> stripe.v1().subscriptions().update(subscriptionId, params, options(idempotencyKey)));
	}

	/** The organization's subscriptions in Stripe (newest first), used to recover a lost link. */
	public List<Subscription> listSubscriptions(String customerId) {
		SubscriptionListParams params = SubscriptionListParams.builder()
				.setCustomer(customerId)
				.setStatus(SubscriptionListParams.Status.ALL)
				.setLimit(20L)
				.addExpand("data.items.data.price")
				.build();
		return call("subscription.list", () -> stripe.v1().subscriptions().list(params, RequestOptions.getDefault()).getData());
	}

	// ── invoices ───────────────────────────────────────────────────────

	/** Invoice preview: what a subscription change would cost, without changing anything. */
	public Invoice previewInvoice(InvoiceCreatePreviewParams params) {
		return call("invoice.preview", () -> stripe.v1().invoices().createPreview(params, RequestOptions.getDefault()));
	}

	/**
	 * Ids of invoice items waiting for the customer's next regular invoice (e.g. credits from an
	 * earlier downgrade). A preview lists them, but an immediate change invoice does not charge them.
	 */
	public java.util.Set<String> pendingInvoiceItemIds(String customerId) {
		com.stripe.param.InvoiceItemListParams params = com.stripe.param.InvoiceItemListParams.builder()
				.setCustomer(customerId)
				.setPending(true)
				.setLimit(100L)
				.build();
		return call("invoiceitem.list", () -> stripe.v1().invoiceItems().list(params, RequestOptions.getDefault()).getData())
				.stream().map(com.stripe.model.InvoiceItem::getId).collect(java.util.stream.Collectors.toSet());
	}

	/** The customer's most recent invoices (newest first). */
	public List<Invoice> listInvoices(String customerId, int limit) {
		com.stripe.param.InvoiceListParams params = com.stripe.param.InvoiceListParams.builder()
				.setCustomer(customerId)
				.setLimit((long) Math.max(1, Math.min(limit, 100)))
				.build();
		return call("invoice.list", () -> stripe.v1().invoices().list(params, RequestOptions.getDefault()).getData());
	}

	public Invoice retrieveInvoice(String invoiceId) {
		return call("invoice.retrieve", () -> stripe.v1().invoices().retrieve(invoiceId));
	}

	/**
	 * "Now" as Stripe sees it for this subscription: the test clock's simulated time when the
	 * subscription runs on one (test mode only), otherwise the real time. Stripe validates dates such as
	 * proration dates against the clock, so sending real time to a clocked subscription fails.
	 */
	public long nowFor(Subscription subscription) {
		String clockId = subscription == null ? null : subscription.getTestClock();
		if (clockId == null) {
			return java.time.Instant.now().getEpochSecond();
		}
		Long frozen = call("test_clock.retrieve", () -> stripe.v1().testHelpers().testClocks().retrieve(clockId)).getFrozenTime();
		return frozen != null ? frozen : java.time.Instant.now().getEpochSecond();
	}

	// ── subscription schedules (changes at the next renewal) ───────────

	public SubscriptionSchedule createScheduleFromSubscription(String subscriptionId, String idempotencyKey) {
		SubscriptionScheduleCreateParams params = SubscriptionScheduleCreateParams.builder()
				.setFromSubscription(subscriptionId)
				.build();
		return call("schedule.create", () -> stripe.v1().subscriptionSchedules().create(params, options(idempotencyKey)));
	}

	public SubscriptionSchedule retrieveSchedule(String scheduleId) {
		return call("schedule.retrieve", () -> stripe.v1().subscriptionSchedules().retrieve(scheduleId));
	}

	public SubscriptionSchedule updateSchedule(String scheduleId, SubscriptionScheduleUpdateParams params, String idempotencyKey) {
		return call("schedule.update", () -> stripe.v1().subscriptionSchedules().update(scheduleId, params, options(idempotencyKey)));
	}

	/** Detaches the schedule; the subscription keeps its current items and renews as it is. */
	public SubscriptionSchedule releaseSchedule(String scheduleId, String idempotencyKey) {
		return call("schedule.release", () -> stripe.v1().subscriptionSchedules().release(scheduleId, options(idempotencyKey)));
	}

	public StripeClient client() {
		return stripe;
	}

	// ── plumbing ───────────────────────────────────────────────────────

	static RequestOptions options(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("Every Stripe write needs an idempotency key");
		}
		return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
	}

	@FunctionalInterface
	interface StripeCall<T> {
		T run() throws StripeException;
	}

	<T> T call(String action, StripeCall<T> fn) {
		requireConfigured();
		try {
			return fn.run();
		} catch (StripeException ex) {
			StripeCallException mapped = StripeCallException.from(ex);
			if (mapped.outcome() == StripeCallException.Outcome.REJECTED) {
				log.warn("Stripe {} rejected: code={} status={} requestId={}", action, ex.getCode(), ex.getStatusCode(), ex.getRequestId());
			} else {
				log.error("Stripe {} outcome {}: code={} status={} requestId={} ({})", action, mapped.outcome(),
						ex.getCode(), ex.getStatusCode(), ex.getRequestId(), ex.getClass().getSimpleName());
			}
			throw mapped;
		}
	}
}
