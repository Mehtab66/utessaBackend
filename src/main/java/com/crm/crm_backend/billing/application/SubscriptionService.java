package com.crm.crm_backend.billing.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.crm.crm_backend.billing.api.dto.BillingDtos.CheckoutResponse;
import com.crm.crm_backend.billing.api.dto.BillingDtos.CheckoutStatusResponse;
import com.crm.crm_backend.billing.api.dto.BillingDtos.CreateSubscriptionRequest;
import com.crm.crm_backend.billing.domain.BillingInterval;
import com.crm.crm_backend.billing.domain.BillingModule;
import com.crm.crm_backend.billing.domain.OperationType;
import com.crm.crm_backend.billing.domain.OrganizationSubscription;
import com.crm.crm_backend.billing.infrastructure.OrganizationSubscriptionRepository;
import com.crm.crm_backend.organization.domain.Organization;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;

/**
 * Starts the organization's one subscription through Stripe Checkout (Stripe's hosted payment
 * page). Subscribing ends the free trial: the first period is charged at checkout and the
 * practice moves straight to its paid plan (the trial is then over for good). Paid status is
 * never taken from the browser — only from Stripe (webhook, or a server-side read of the
 * Checkout session).
 */
@Service
public class SubscriptionService {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
	/** The request as fingerprinted for idempotency (modules sorted). */
	record NormalizedCreate(List<String> modules, int seats, BillingInterval interval) {
	}

	private final BillingAccess access;
	private final PricingCatalog catalog;
	private final StripeGateway gateway;
	private final StripeProperties props;
	private final IdempotencyService idempotency;
	private final BillingCustomerService customers;
	private final SubscriptionSyncService sync;
	private final OrganizationSubscriptionRepository subscriptions;
	private final BillingQueryService queries;
	private final TransactionTemplate tx;

	public SubscriptionService(BillingAccess access, PricingCatalog catalog, StripeGateway gateway, StripeProperties props,
			IdempotencyService idempotency, BillingCustomerService customers, SubscriptionSyncService sync,
			OrganizationSubscriptionRepository subscriptions, BillingQueryService queries, PlatformTransactionManager txManager) {
		this.access = access;
		this.catalog = catalog;
		this.gateway = gateway;
		this.props = props;
		this.idempotency = idempotency;
		this.customers = customers;
		this.sync = sync;
		this.subscriptions = subscriptions;
		this.queries = queries;
		this.tx = new TransactionTemplate(txManager);
	}

	/** POST /billing/subscriptions — returns the Stripe Checkout URL. */
	public CheckoutResponse startCheckout(UserPrincipal principal, String idempotencyKey, CreateSubscriptionRequest request) {
		UUID orgId = access.requireBillingAdmin(principal);
		NormalizedCreate normalized = normalize(orgId, request);
		// A retry of a finished checkout gets its original answer, even if the subscription is live by now.
		java.util.Optional<CheckoutResponse> done = idempotency.replayIfFinished(orgId, idempotencyKey,
				OperationType.CREATE_SUBSCRIPTION, normalized, CheckoutResponse.class);
		if (done.isPresent()) {
			return done.get();
		}
		requireNoLiveSubscription(orgId);

		return idempotency.execute(orgId, principal.userId(), idempotencyKey, OperationType.CREATE_SUBSCRIPTION, normalized,
				CheckoutResponse.class, ctx -> {
					requireNoLiveSubscription(orgId);
					String customerId = customers.ensureCustomer(orgId, ctx, null);
					closePreviousCheckout(orgId, ctx);

					Organization org = access.organization(orgId);
					String base = props.frontendUrl().replaceAll("/+$", "");

					// No trial on the subscription: buying ends the free trial and the first period is
					// charged now, so the practice becomes a paying customer as soon as checkout succeeds.
					SessionCreateParams.SubscriptionData.Builder data = SessionCreateParams.SubscriptionData.builder()
							.putMetadata(StripeGateway.ORG_METADATA, orgId.toString())
							.setDescription(org.getName());
					SessionCreateParams.Builder params = SessionCreateParams.builder()
							.setMode(SessionCreateParams.Mode.SUBSCRIPTION)
							.setCustomer(customerId)
							.setClientReferenceId(orgId.toString())
							.putMetadata(StripeGateway.ORG_METADATA, orgId.toString())
							// Keep the card for the renewals that follow.
							.setPaymentMethodCollection(SessionCreateParams.PaymentMethodCollection.ALWAYS)
							.setBillingAddressCollection(SessionCreateParams.BillingAddressCollection.AUTO)
							.setSubscriptionData(data.build())
							.setSuccessUrl(base + "/app/billing?checkout=success&session_id={CHECKOUT_SESSION_ID}")
							.setCancelUrl(base + "/app/billing?checkout=cancelled");
					for (String key : normalized.modules()) {
						BillingModule module = BillingModule.fromKey(key).orElseThrow();
						params.addLineItem(SessionCreateParams.LineItem.builder()
								.setPrice(catalog.modulePrice(module, normalized.interval()).priceId())
								.setQuantity(1L)
								.build());
					}
					if (normalized.seats() > 0) {
						params.addLineItem(SessionCreateParams.LineItem.builder()
								.setPrice(catalog.seatPrice(normalized.interval()).priceId())
								.setQuantity((long) normalized.seats())
								.build());
					}

					Session session = gateway.createCheckoutSession(params.build(), ctx.stripeKey("checkout"));
					Instant expiresAt = SubscriptionSyncService.instant(session.getExpiresAt());
					tx.executeWithoutResult(s -> {
						OrganizationSubscription row = subscriptions.lockByOrganizationId(orgId).orElseThrow();
						row.setPendingCheckoutSessionId(session.getId());
						row.setPendingCheckoutExpiresAt(expiresAt);
						subscriptions.save(row);
					});
					log.info("Checkout {} started for organization {} ({} modules, {} seats, {}; charged at checkout)",
							session.getId(), orgId, normalized.modules().size(), normalized.seats(), normalized.interval());
					return new IdempotencyService.Result<>(
							new CheckoutResponse(session.getId(), session.getUrl(), expiresAt, null), session.getId());
				});
	}

	/**
	 * GET /billing/checkout/{sessionId} — called by the success page. Reads the session from
	 * Stripe on the server (the browser's word is never trusted) and syncs the subscription.
	 */
	public CheckoutStatusResponse checkoutStatus(UserPrincipal principal, String sessionId) {
		UUID orgId = access.requireBillingAdmin(principal);
		if (sessionId == null || !sessionId.startsWith("cs_")) {
			throw ApiException.notFound("Checkout session not found.");
		}
		Session session;
		try {
			session = gateway.retrieveCheckoutSession(sessionId);
		} catch (StripeCallException ex) {
			if (ex.outcome() == StripeCallException.Outcome.REJECTED) {
				throw ApiException.notFound("Checkout session not found.");
			}
			throw ex;
		}
		String owner = session.getClientReferenceId() != null ? session.getClientReferenceId()
				: Optional.ofNullable(session.getMetadata()).map(m -> m.get(StripeGateway.ORG_METADATA)).orElse(null);
		if (!orgId.toString().equals(owner)) {
			// Another organization's session: reveal nothing.
			throw ApiException.notFound("Checkout session not found.");
		}
		String status = session.getStatus() == null ? "open" : session.getStatus();
		if ("complete".equals(status) && session.getSubscription() != null) {
			Subscription s = gateway.retrieveSubscription(session.getSubscription());
			SubscriptionSyncService.Outcome outcome = sync.sync(s);
			if (outcome == SubscriptionSyncService.Outcome.DUPLICATE) {
				log.error("Checkout {} produced a second subscription {} for organization {}", sessionId, s.getId(), orgId);
			}
		} else if ("expired".equals(status)) {
			clearPendingCheckout(orgId, sessionId);
		}
		return new CheckoutStatusResponse(status, queries.subscription(orgId));
	}

	// ── rules ───────────────────────────────────────────────────────────

	private NormalizedCreate normalize(UUID orgId, CreateSubscriptionRequest r) {
		TreeSet<String> modules = new TreeSet<>();
		for (String raw : r.modules()) {
			BillingModule module = BillingModule.fromKey(raw)
					.orElseThrow(() -> ApiException.badRequest("Unknown module: " + raw));
			modules.add(module.key());
		}
		int members = access.billableMembers(orgId);
		if (r.seats() < members) {
			throw ApiException.badRequest("Your practice has " + members + " team member" + (members == 1 ? "" : "s")
					+ " besides the owner. Choose at least " + members + " seat" + (members == 1 ? "" : "s") + ".");
		}
		return new NormalizedCreate(List.copyOf(modules), r.seats(), r.interval());
	}

	/** One subscription per organization: block a new one while the current one is alive. */
	private void requireNoLiveSubscription(UUID orgId) {
		subscriptions.findByOrganizationId(orgId).ifPresent(row -> {
			if (row.getStripeSubscriptionId() != null && !SubscriptionSyncService.ENDED.contains(row.getSubscriptionStatus())) {
				throw ApiException.conflict("This practice already has a subscription. Change it instead of starting a new one.");
			}
		});
	}

	/**
	 * Expires the previous open Checkout session so two sessions can never both complete. If it
	 * already completed, its subscription is synced and this checkout is refused.
	 */
	private void closePreviousCheckout(UUID orgId, IdempotencyService.Context ctx) {
		OrganizationSubscription row = subscriptions.findByOrganizationId(orgId).orElse(null);
		String previous = row == null ? null : row.getPendingCheckoutSessionId();
		if (previous == null) {
			return;
		}
		Session old = gateway.retrieveCheckoutSession(previous);
		if ("complete".equals(old.getStatus())) {
			if (old.getSubscription() != null) {
				sync.sync(gateway.retrieveSubscription(old.getSubscription()));
			}
			throw ApiException.conflict("A checkout for this practice was just completed. Refresh to see your subscription.");
		}
		if ("open".equals(old.getStatus())) {
			gateway.expireCheckoutSession(previous, ctx.stripeKey("expire-" + previous));
		}
		clearPendingCheckout(orgId, previous);
	}

	private void clearPendingCheckout(UUID orgId, String sessionId) {
		tx.executeWithoutResult(s -> subscriptions.lockByOrganizationId(orgId).ifPresent(row -> {
			if (sessionId.equals(row.getPendingCheckoutSessionId())) {
				row.setPendingCheckoutSessionId(null);
				row.setPendingCheckoutExpiresAt(null);
				subscriptions.save(row);
			}
		}));
	}

	static Map<String, String> orgMetadata(UUID orgId) {
		return Map.of(StripeGateway.ORG_METADATA, orgId.toString());
	}
}
