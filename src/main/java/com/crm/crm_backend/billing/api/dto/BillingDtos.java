package com.crm.crm_backend.billing.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.crm.crm_backend.billing.domain.BillingInterval;
import com.crm.crm_backend.billing.domain.ChangeTiming;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Billing API request/response types. Requests carry only module keys, a seat count and an
 * interval — never price IDs, amounts or organization IDs. Responses never expose Stripe SDK
 * objects. Amounts are in the currency's minor unit (e.g. cents).
 */
public final class BillingDtos {

	private BillingDtos() {
	}

	// ── pricing ────────────────────────────────────────────────────────

	public record Money(long amount, String currency) {
	}

	public record ModulePrice(String key, String label, Money monthly, Money yearly) {
	}

	public record PricingResponse(
			String currency,
			int trialDays,
			/** Team members (besides the owner) allowed during the trial. */
			int trialMaxMembers,
			/** Owner seats are free; seats are charged per team member besides the owner. */
			boolean ownerSeatFree,
			String publishableKey,
			List<ModulePrice> modules,
			Money seatMonthly,
			Money seatYearly
	) {
	}

	// ── customer ───────────────────────────────────────────────────────

	public record AddressDto(
			@Size(max = 200) String line1,
			@Size(max = 200) String line2,
			@Size(max = 100) String city,
			@Size(max = 100) String state,
			@Size(max = 20) String postalCode,
			@Pattern(regexp = "^[A-Za-z]{2}$", message = "Country must be a 2-letter ISO code (e.g. US).") String country
	) {
	}

	/** Billing details sent to Stripe (shown on invoices). All optional. */
	public record BillingDetailsRequest(
			@Size(max = 200) String name,
			@Email(message = "Enter a valid billing email.") @Size(max = 255) String email,
			@Size(max = 30) String phone,
			@Valid AddressDto address
	) {
	}

	public record CustomerResponse(
			boolean exists,
			String name,
			String email,
			String phone,
			AddressDto address
	) {
	}

	// ── subscription creation ──────────────────────────────────────────

	public record CreateSubscriptionRequest(
			/** Module keys: crm, ops, mkt, tc. */
			@NotEmpty(message = "Choose at least one module.") Set<@NotNull String> modules,
			/** Paid team-member seats (the owner is free). */
			@Min(value = 0, message = "Seats can't be negative.") @Max(value = 10000, message = "Contact us for more than 10,000 seats.") int seats,
			@NotNull(message = "Choose monthly or yearly billing.") BillingInterval interval
	) {
	}

	public record CheckoutResponse(
			String sessionId,
			/** Stripe-hosted page where the customer enters payment details. */
			String checkoutUrl,
			Instant expiresAt,
			/** When the first charge happens (end of the free trial), or null if charged right away. */
			Instant trialEndsAt
	) {
	}

	public record CheckoutStatusResponse(
			/** open | complete | expired */
			String status,
			SubscriptionResponse subscription
	) {
	}

	// ── subscription changes ───────────────────────────────────────────

	/**
	 * The subscription the customer wants: modules, seats and interval. Omitted fields keep their
	 * current value. {@code previewToken} comes from POST /billing/preview and is required when
	 * the change charges money now.
	 */
	public record ChangeRequest(
			Set<@NotNull String> modules,
			@Min(value = 0, message = "Seats can't be negative.") @Max(value = 10000, message = "Contact us for more than 10,000 seats.") Integer seats,
			BillingInterval interval,
			ChangeTiming timing,
			@Size(max = 1000) String previewToken
	) {
	}

	public record SeatsRequest(
			@NotNull(message = "Enter the number of seats.") @Min(value = 0, message = "Seats can't be negative.")
			@Max(value = 10000, message = "Contact us for more than 10,000 seats.") Integer seats,
			ChangeTiming timing,
			@Size(max = 1000) String previewToken
	) {
	}

	public record IntervalRequest(
			@NotNull(message = "Choose monthly or yearly billing.") BillingInterval interval,
			ChangeTiming timing,
			@Size(max = 1000) String previewToken
	) {
	}

	public record PreviewLine(String description, Money amount) {
	}

	public record PreviewResponse(
			/** UPGRADE | DOWNGRADE | INTERVAL */
			String changeType,
			ChangeTiming timing,
			/** When the change takes effect. */
			Instant effectiveAt,
			/** Charged right away when confirmed (0 for trial and next-renewal changes). */
			Money amountDueNow,
			/** The next regular invoice after the change. */
			Money nextInvoiceAmount,
			Instant nextInvoiceAt,
			/** Invoice lines (prorations for immediate changes; the new plan for scheduled ones). */
			List<PreviewLine> lines,
			List<String> addedModules,
			List<String> removedModules,
			int seatsFrom,
			int seatsTo,
			BillingInterval intervalFrom,
			BillingInterval intervalTo,
			boolean inTrial,
			/** Send back to confirm; valid for 15 minutes and only for this exact change. */
			String previewToken,
			Instant previewExpiresAt
	) {
	}

	public record ChangeResponse(
			/** APPLIED (now) | SCHEDULED (next renewal) | PAYMENT_REQUIRED (not applied until paid) */
			String result,
			Instant effectiveAt,
			/** Amount invoiced for this change (minor units), when charged now. */
			Money amountInvoiced,
			/** Stripe page where the customer can complete a payment that needs action. */
			String paymentUrl,
			SubscriptionResponse subscription
	) {
	}

	// ── cancellation ───────────────────────────────────────────────────

	public enum CancelMode { IMMEDIATE, PERIOD_END }

	public record CancelRequest(
			/** PERIOD_END (default): keep access until the period / trial ends. IMMEDIATE: end now. */
			CancelMode mode
	) {
	}

	// ── entitlements ───────────────────────────────────────────────────

	public record EntitlementResponse(
			/** TRIAL | ACTIVE | PAST_DUE | RESTRICTED | INACTIVE */
			String state,
			boolean trialActive,
			Instant trialEndsAt,
			boolean paidActive,
			/** Module keys usable right now: crm, ops, mkt, tc */
			List<String> modules,
			/** Team members allowed besides the owner. */
			int maxMembers,
			int members,
			int seatsLeft,
			boolean paymentOverdue,
			Instant gracePeriodEndsAt,
			boolean cancellationScheduled,
			Instant accessEndsAt,
			String subscriptionStatus
	) {
	}

	// ── invoices ───────────────────────────────────────────────────────

	public record InvoiceResponse(
			String number,
			/** draft | open | paid | uncollectible | void */
			String status,
			String billingReason,
			Money amountDue,
			Money amountPaid,
			Money amountRemaining,
			Instant periodStart,
			Instant periodEnd,
			Instant createdAt,
			Instant paidAt,
			int attemptCount,
			Instant nextPaymentAttempt,
			String hostedInvoiceUrl,
			String invoicePdfUrl
	) {
	}

	// ── subscription state ─────────────────────────────────────────────

	public record ItemResponse(
			String key,
			String label,
			int quantity,
			Money unitPrice,
			BillingInterval interval,
			String status
	) {
	}

	public record TrialInfo(
			boolean active,
			Instant startsAt,
			Instant endsAt,
			long daysLeft,
			int maxMembers
	) {
	}

	public record SeatInfo(
			/** Seats on the paid subscription. */
			int purchased,
			/** Team members using a seat right now (owner excluded). */
			int used
	) {
	}

	public record SubscriptionResponse(
			/** none | trialing | active | past_due | unpaid | canceled | incomplete | incomplete_expired | paused */
			String status,
			BillingInterval interval,
			TrialInfo trial,
			Instant currentPeriodStart,
			Instant currentPeriodEnd,
			boolean cancelAtPeriodEnd,
			Instant cancelAt,
			Instant canceledAt,
			List<ItemResponse> items,
			SeatInfo seats,
			boolean hasCustomer,
			String pendingCheckoutSessionId,
			Instant pendingCheckoutExpiresAt,
			Instant paymentFailedAt,
			Instant gracePeriodEndsAt,
			BillingInterval scheduledInterval,
			Instant scheduledChangeAt
	) {
	}
}
