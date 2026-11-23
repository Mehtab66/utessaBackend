package com.crm.crm_backend.billing.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.billing.api.dto.BillingDtos.BillingDetailsRequest;
import com.crm.crm_backend.billing.api.dto.BillingDtos.ChangeRequest;
import com.crm.crm_backend.billing.api.dto.BillingDtos.ChangeResponse;
import com.crm.crm_backend.billing.api.dto.BillingDtos.IntervalRequest;
import com.crm.crm_backend.billing.api.dto.BillingDtos.PreviewResponse;
import com.crm.crm_backend.billing.api.dto.BillingDtos.SeatsRequest;
import com.crm.crm_backend.billing.api.dto.BillingDtos.CheckoutResponse;
import com.crm.crm_backend.billing.api.dto.BillingDtos.CheckoutStatusResponse;
import com.crm.crm_backend.billing.api.dto.BillingDtos.CreateSubscriptionRequest;
import com.crm.crm_backend.billing.api.dto.BillingDtos.CustomerResponse;
import com.crm.crm_backend.billing.api.dto.BillingDtos.PricingResponse;
import com.crm.crm_backend.billing.api.dto.BillingDtos.SubscriptionResponse;
import com.crm.crm_backend.billing.application.BillingCustomerService;
import com.crm.crm_backend.billing.application.BillingQueryService;
import com.crm.crm_backend.billing.application.SubscriptionChangeService;
import com.crm.crm_backend.billing.application.SubscriptionService;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

/**
 * Subscription billing for the caller's own practice (taken from the session, never the URL).
 * Every request that changes billing needs an {@code Idempotency-Key} header: send a new key for
 * each new change, and the same key when retrying the same change.
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

	public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

	private final BillingQueryService queries;
	private final BillingCustomerService customers;
	private final SubscriptionService subscriptions;
	private final SubscriptionChangeService changes;
	private final com.crm.crm_backend.billing.application.CancellationService cancellation;
	private final com.crm.crm_backend.billing.application.EntitlementService entitlements;
	private final com.crm.crm_backend.billing.application.BillingAccess access;

	public BillingController(BillingQueryService queries, BillingCustomerService customers, SubscriptionService subscriptions,
			SubscriptionChangeService changes, com.crm.crm_backend.billing.application.CancellationService cancellation,
			com.crm.crm_backend.billing.application.EntitlementService entitlements,
			com.crm.crm_backend.billing.application.BillingAccess access) {
		this.queries = queries;
		this.customers = customers;
		this.subscriptions = subscriptions;
		this.changes = changes;
		this.cancellation = cancellation;
		this.entitlements = entitlements;
		this.access = access;
	}

	/** What the caller's practice may use right now (any member; drives what the app shows). */
	@GetMapping("/entitlements")
	public com.crm.crm_backend.billing.api.dto.BillingDtos.EntitlementResponse entitlements(
			@AuthenticationPrincipal UserPrincipal principal) {
		var e = entitlements.forOrganization(access.requireMember(principal));
		return new com.crm.crm_backend.billing.api.dto.BillingDtos.EntitlementResponse(e.state().name(), e.trialActive(),
				e.trialEndsAt(), e.paidActive(), e.modules().stream().sorted().toList(), e.maxMembers(), e.members(),
				e.seatsLeft(), e.paymentOverdue(), e.gracePeriodEndsAt(), e.cancellationScheduled(), e.accessEndsAt(),
				e.subscriptionStatus());
	}

	/** Cancel now, or at the end of the period / trial (default). */
	@PostMapping("/cancel")
	public ChangeResponse cancel(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
			@RequestBody(required = false) com.crm.crm_backend.billing.api.dto.BillingDtos.CancelRequest request) {
		return cancellation.cancel(principal, idempotencyKey, request);
	}

	/** Undo a cancellation scheduled for period end. */
	@PostMapping("/reactivate")
	public ChangeResponse reactivate(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
		return cancellation.reactivate(principal, idempotencyKey);
	}

	@GetMapping("/pricing")
	public PricingResponse pricing(@AuthenticationPrincipal UserPrincipal principal) {
		return queries.pricing(principal);
	}

	@GetMapping("/subscription")
	public SubscriptionResponse subscription(@AuthenticationPrincipal UserPrincipal principal) {
		return queries.subscription(principal);
	}

	@GetMapping("/invoices")
	public java.util.List<com.crm.crm_backend.billing.api.dto.BillingDtos.InvoiceResponse> invoices(
			@AuthenticationPrincipal UserPrincipal principal,
			@org.springframework.web.bind.annotation.RequestParam(defaultValue = "24") int limit) {
		return queries.invoices(principal, limit);
	}

	@GetMapping("/customer")
	public CustomerResponse customer(@AuthenticationPrincipal UserPrincipal principal) {
		return customers.get(principal);
	}

	@PostMapping("/customer")
	public CustomerResponse createCustomer(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody(required = false) BillingDetailsRequest details) {
		return customers.create(principal, idempotencyKey, details);
	}

	@PutMapping("/customer")
	public CustomerResponse updateCustomer(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody BillingDetailsRequest details) {
		return customers.update(principal, idempotencyKey, details);
	}

	/** Starts the subscription: returns the Stripe Checkout URL to send the customer to. */
	@PostMapping("/subscriptions")
	@ResponseStatus(HttpStatus.CREATED)
	public CheckoutResponse createSubscription(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody CreateSubscriptionRequest request) {
		return subscriptions.startCheckout(principal, idempotencyKey, request);
	}

	/** What a change would cost (now and at the next invoice). Changes nothing; returns a previewToken. */
	@PostMapping("/preview")
	public PreviewResponse preview(
			@AuthenticationPrincipal UserPrincipal principal,
			@Valid @RequestBody ChangeRequest request) {
		return changes.preview(principal, request);
	}

	/** Add modules and/or seats (charged now, prorated; needs the previewToken). */
	@PostMapping("/upgrade")
	public ChangeResponse upgrade(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody ChangeRequest request) {
		return changes.upgrade(principal, idempotencyKey, request);
	}

	/** Remove modules and/or seats (default: at the next renewal). */
	@PostMapping("/downgrade")
	public ChangeResponse downgrade(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody ChangeRequest request) {
		return changes.downgrade(principal, idempotencyKey, request);
	}

	/** Change the number of paid team-member seats. */
	@PostMapping("/team-members")
	public ChangeResponse teamMembers(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody SeatsRequest request) {
		return changes.seats(principal, idempotencyKey, request);
	}

	/** Switch monthly ↔ yearly, now or at the next renewal. */
	@PostMapping("/change-billing-cycle")
	public ChangeResponse changeBillingCycle(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody IntervalRequest request) {
		return changes.changeInterval(principal, idempotencyKey, request);
	}

	/** Undo a change booked for the next renewal. */
	@PostMapping("/scheduled-change/cancel")
	public ChangeResponse cancelScheduledChange(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
		return changes.cancelScheduledChange(principal, idempotencyKey);
	}

	/** Re-reads the subscription from Stripe (e.g. if a webhook was missed). */
	@PostMapping("/subscription/refresh")
	public SubscriptionResponse refresh(@AuthenticationPrincipal UserPrincipal principal) {
		return changes.refresh(principal);
	}

	/** Called by the Checkout success page; confirms the result with Stripe on the server. */
	@GetMapping("/checkout/{sessionId}")
	public CheckoutStatusResponse checkoutStatus(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable String sessionId) {
		return subscriptions.checkoutStatus(principal, sessionId);
	}
}
