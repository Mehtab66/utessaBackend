package com.crm.crm_backend.billing.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.billing.application.StripeWebhookService;
import com.crm.crm_backend.billing.application.StripeWebhookService.InvalidSignatureException;

/**
 * Stripe → Utessa webhooks. Public (Stripe can't log in); trust comes only from the signature.
 * Register https://YOUR-API-HOST/api/v1/stripe/webhook in Stripe and put its signing secret in
 * STRIPE_WEBHOOK_SECRET.
 */
@RestController
@RequestMapping("/api/v1/stripe")
public class StripeWebhookController {

	private final StripeWebhookService webhooks;

	public StripeWebhookController(StripeWebhookService webhooks) {
		this.webhooks = webhooks;
	}

	/** Raw bytes: the signature is computed over the exact payload Stripe sent. */
	@PostMapping("/webhook")
	public ResponseEntity<Map<String, String>> receive(
			@RequestBody byte[] payload,
			@RequestHeader(value = "Stripe-Signature", required = false) String signature) {
		try {
			StripeWebhookService.Outcome outcome = webhooks.receive(payload, signature);
			return ResponseEntity.ok(Map.of("received", "true", "outcome", outcome.name().toLowerCase()));
		} catch (InvalidSignatureException ex) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
		}
	}
}
