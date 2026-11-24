package com.crm.crm_backend.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.crm.crm_backend.billing.application.BillingTestData;
import com.crm.crm_backend.billing.application.EntitlementService;
import com.crm.crm_backend.billing.application.StripeWebhookService;
import com.crm.crm_backend.billing.application.StripeWebhookService.InvalidSignatureException;
import com.crm.crm_backend.billing.domain.BillingInterval;
import com.crm.crm_backend.billing.domain.OrganizationSubscription;
import com.crm.crm_backend.billing.domain.SubscriptionItem;
import com.crm.crm_backend.billing.domain.SubscriptionItemStatus;
import com.crm.crm_backend.billing.domain.WebhookProcessingStatus;
import com.crm.crm_backend.billing.infrastructure.StripeWebhookEventRepository;
import com.crm.crm_backend.billing.infrastructure.SubscriptionInvoiceRepository;
import com.crm.crm_backend.billing.infrastructure.SubscriptionItemRepository;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.net.ApiResource;

/** Webhook verification, de-duplication, order-independence and the payment-failure lifecycle. */
class StripeWebhookIntegrationTest extends BillingIntegrationTestBase {

	/** Same value as billing.webhook-secret in application-test.properties. */
	private static final String SECRET = "whsec_test_integration_secret";

	@Autowired
	StripeWebhookService webhooks;
	@Autowired
	StripeWebhookEventRepository events;
	@Autowired
	SubscriptionItemRepository items;
	@Autowired
	SubscriptionInvoiceRepository invoices;
	@Autowired
	EntitlementService entitlements;

	UUID org;
	String customerId;
	String subscriptionId;

	@BeforeEach
	void paidPractice() {
		org = practice(20).getId();
		customerId = "cus_it_" + UUID.randomUUID().toString().substring(0, 8);
		subscriptionId = "sub_it_" + UUID.randomUUID().toString().substring(0, 8);
		billingRow(org, customerId, subscriptionId);
	}

	@Test
	void unsignedOrTamperedEventsAreRejectedAndNotStored() {
		String payload = event("evt_bad_" + UUID.randomUUID(), "customer.subscription.updated", subscriptionId, "subscription", false);
		assertThatThrownBy(() -> webhooks.receive(bytes(payload), null)).isInstanceOf(InvalidSignatureException.class);
		assertThatThrownBy(() -> webhooks.receive(bytes(payload), "t=1,v1=" + "0".repeat(64))).isInstanceOf(InvalidSignatureException.class);
		String signature = sign(payload, Instant.now().getEpochSecond());
		assertThatThrownBy(() -> webhooks.receive(bytes(payload.replace("false", "true")), signature))
				.isInstanceOf(InvalidSignatureException.class);
		String old = sign(payload, Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond());
		assertThatThrownBy(() -> webhooks.receive(bytes(payload), old)).isInstanceOf(InvalidSignatureException.class);
		assertThat(events.findAll()).noneMatch(e -> e.getStripeEventId().startsWith("evt_bad_") && e.getStripeObjectId().equals(subscriptionId));
	}

	@Test
	void subscriptionStateComesFromStripeNotFromThePayloadAndEachEventIsProcessedOnce() {
		when(gateway.retrieveSubscription(subscriptionId)).thenReturn(subscription("active", 3));
		String eventId = "evt_it_" + UUID.randomUUID();
		String payload = event(eventId, "customer.subscription.updated", subscriptionId, "subscription", false);

		assertThat(webhooks.receive(bytes(payload), sign(payload, now()))).isEqualTo(StripeWebhookService.Outcome.PROCESSED);
		assertThat(webhooks.receive(bytes(payload), sign(payload, now()))).isEqualTo(StripeWebhookService.Outcome.DUPLICATE);
		verify(gateway, times(1)).retrieveSubscription(subscriptionId);

		OrganizationSubscription row = subscriptions.findByOrganizationId(org).orElseThrow();
		assertThat(row.getSubscriptionStatus()).isEqualTo("active");
		assertThat(row.getBillingInterval()).isEqualTo(BillingInterval.MONTHLY);
		assertThat(row.getPaidBillingStartedAt()).isNotNull();
		List<SubscriptionItem> live = items.findBySubscriptionIdAndStatusIn(row.getId(), List.of(SubscriptionItemStatus.ACTIVE));
		assertThat(live).extracting(SubscriptionItem::getModuleKey).containsExactlyInAnyOrder("crm", "seat");
		assertThat(live).filteredOn(SubscriptionItem::isSeat).extracting(SubscriptionItem::getQuantity).containsExactly(3);
		assertThat(events.findByStripeEventId(eventId).orElseThrow().getProcessingStatus()).isEqualTo(WebhookProcessingStatus.PROCESSED);
	}

	@Test
	void anOldEventArrivingLateStillLeavesTheCurrentStripeState() {
		when(gateway.retrieveSubscription(subscriptionId)).thenReturn(subscription("active", 4));
		String late = event("evt_it_" + UUID.randomUUID(), "customer.subscription.updated", subscriptionId, "subscription", false)
				.replace("\"created\":" + now(), "\"created\":" + (now() - 7200));
		webhooks.receive(bytes(late), sign(late, now()));
		OrganizationSubscription row = subscriptions.findByOrganizationId(org).orElseThrow();
		assertThat(items.findBySubscriptionIdAndStatusIn(row.getId(), List.of(SubscriptionItemStatus.ACTIVE)))
				.filteredOn(SubscriptionItem::isSeat).extracting(SubscriptionItem::getQuantity).containsExactly(4);
	}

	@Test
	void failedRenewalStartsGraceAndPaymentRestoresAccess() {
		String invoiceId = "in_it_" + UUID.randomUUID().toString().substring(0, 8);
		when(gateway.retrieveSubscription(subscriptionId)).thenReturn(subscription("past_due", 1));
		when(gateway.retrieveInvoice(invoiceId)).thenReturn(invoice(invoiceId, "open", 0));

		String failed = event("evt_it_" + UUID.randomUUID(), "invoice.payment_failed", invoiceId, "invoice", false);
		webhooks.receive(bytes(failed), sign(failed, now()));

		OrganizationSubscription row = subscriptions.findByOrganizationId(org).orElseThrow();
		assertThat(row.getPaymentFailedAt()).isNotNull();
		assertThat(row.getGracePeriodEndsAt()).isBetween(Instant.now().plus(Duration.ofDays(6)), Instant.now().plus(Duration.ofDays(8)));
		assertThat(entitlements.forOrganization(org).state()).isEqualTo(EntitlementService.State.PAST_DUE);
		assertThat(entitlements.forOrganization(org).modules()).containsExactly("crm");

		when(gateway.retrieveSubscription(subscriptionId)).thenReturn(subscription("active", 1));
		when(gateway.retrieveInvoice(invoiceId)).thenReturn(invoice(invoiceId, "paid", 4000));
		String paid = event("evt_it_" + UUID.randomUUID(), "invoice.paid", invoiceId, "invoice", false);
		webhooks.receive(bytes(paid), sign(paid, now()));

		row = subscriptions.findByOrganizationId(org).orElseThrow();
		assertThat(row.getPaymentFailedAt()).isNull();
		assertThat(row.getGracePeriodEndsAt()).isNull();
		assertThat(entitlements.forOrganization(org).state()).isEqualTo(EntitlementService.State.ACTIVE);
		assertThat(invoices.findByStripeInvoiceId(invoiceId).orElseThrow().getInvoiceStatus()).isEqualTo("paid");
	}

	@Test
	void aVoidedInvoiceNoLongerShowsAsOpen() {
		String invoiceId = "in_it_" + UUID.randomUUID().toString().substring(0, 8);
		when(gateway.retrieveSubscription(subscriptionId)).thenReturn(subscription("active", 1));
		when(gateway.retrieveInvoice(invoiceId)).thenReturn(invoice(invoiceId, "open", 0));
		String failed = event("evt_it_" + UUID.randomUUID(), "invoice.payment_failed", invoiceId, "invoice", false);
		webhooks.receive(bytes(failed), sign(failed, now()));
		assertThat(invoices.findByStripeInvoiceId(invoiceId).orElseThrow().getInvoiceStatus()).isEqualTo("open");

		when(gateway.retrieveInvoice(invoiceId)).thenReturn(invoice(invoiceId, "void", 0));
		String voided = event("evt_it_" + UUID.randomUUID(), "invoice.voided", invoiceId, "invoice", false);
		assertThat(webhooks.receive(bytes(voided), sign(voided, now()))).isEqualTo(StripeWebhookService.Outcome.PROCESSED);

		assertThat(invoices.findByStripeInvoiceId(invoiceId).orElseThrow().getInvoiceStatus()).isEqualTo("void");
	}

	@Test
	void liveModeEventIsIgnoredWithATestKey() {
		String payload = event("evt_it_" + UUID.randomUUID(), "customer.subscription.updated", subscriptionId, "subscription", true);
		assertThat(webhooks.receive(bytes(payload), sign(payload, now()))).isEqualTo(StripeWebhookService.Outcome.IGNORED);
	}

	// ── Stripe-shaped fixtures ──────────────────────────────────────────

	private Subscription subscription(String status, int seats) {
		long start = now() - 86400;
		long end = start + 30 * 86400;
		String json = """
				{"id":"%s","object":"subscription","status":"%s","customer":"%s","cancel_at_period_end":false,
				 "metadata":{"organization_id":"%s"},
				 "items":{"object":"list","data":[
				   {"id":"si_crm_%s","object":"subscription_item","quantity":1,"current_period_start":%d,"current_period_end":%d,
				    "price":{"id":"%s","object":"price","currency":"usd","unit_amount":3000,"recurring":{"interval":"month","interval_count":1,"usage_type":"licensed"}}},
				   {"id":"si_seat_%s","object":"subscription_item","quantity":%d,"current_period_start":%d,"current_period_end":%d,
				    "price":{"id":"%s","object":"price","currency":"usd","unit_amount":1000,"recurring":{"interval":"month","interval_count":1,"usage_type":"licensed"}}}
				 ]}}
				""".formatted(subscriptionId, status, customerId, org, subscriptionId, start, end,
				BillingTestData.priceId("crm", BillingInterval.MONTHLY), subscriptionId, seats, start, end,
				BillingTestData.priceId("seat", BillingInterval.MONTHLY));
		return ApiResource.GSON.fromJson(json, Subscription.class);
	}

	private Invoice invoice(String id, String status, long paid) {
		String json = """
				{"id":"%s","object":"invoice","customer":"%s","status":"%s","currency":"usd","billing_reason":"subscription_cycle",
				 "amount_due":4000,"amount_paid":%d,"amount_remaining":%d,"attempt_count":1,"created":%d,
				 "parent":{"type":"subscription_details","subscription_details":{"subscription":"%s"}}}
				""".formatted(id, customerId, status, paid, 4000 - paid, now(), subscriptionId);
		return ApiResource.GSON.fromJson(json, Invoice.class);
	}

	private static String event(String id, String type, String objectId, String objectType, boolean livemode) {
		return "{\"id\":\"" + id + "\",\"object\":\"event\",\"api_version\":\"2025-10-29.clover\",\"created\":" + now()
				+ ",\"livemode\":" + livemode + ",\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\"" + objectId
				+ "\",\"object\":\"" + objectType + "\",\"status\":\"garbage-from-payload\"}}}";
	}

	/** Stripe's scheme: HMAC-SHA256 of "timestamp.payload" with the endpoint secret. */
	private static String sign(String payload, long timestamp) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			String sig = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
			return "t=" + timestamp + ",v1=" + sig;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static long now() {
		return Instant.now().getEpochSecond();
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}
}
