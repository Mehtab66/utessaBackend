package com.crm.crm_backend.billing.application;

import java.text.NumberFormat;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.crm.crm_backend.identity.domain.User;
import com.crm.crm_backend.organization.domain.Organization;

import jakarta.annotation.PreDestroy;

/**
 * Emails the practice owner about billing events. Sending happens off the webhook thread so a slow
 * mail server never delays Stripe's webhook response. Contains no card details.
 */
@Component
public class BillingEmailNotifier {

	private static final Logger log = LoggerFactory.getLogger(BillingEmailNotifier.class);
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.US).withZone(ZoneOffset.UTC);

	private final BillingAccess access;
	private final JavaMailSender mail;
	private final StripeProperties props;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "billing-mail");
		t.setDaemon(true);
		return t;
	});

	@Value("${app.mail.from:info@utessa.com}")
	private String from;

	public BillingEmailNotifier(BillingAccess access, @Autowired(required = false) JavaMailSender mail, StripeProperties props) {
		this.access = access;
		this.mail = mail;
		this.props = props;
	}

	@EventListener
	public void on(BillingNotification n) {
		executor.submit(() -> send(n));
	}

	private void send(BillingNotification n) {
		try {
			Organization org = access.organization(n.organizationId());
			User owner = access.owner(n.organizationId()).orElse(null);
			if (owner == null || owner.getEmail() == null) {
				log.warn("Billing notification {} for organization {}: no owner email", n.type(), n.organizationId());
				return;
			}
			String[] content = content(n, org.getName());
			if (mail == null) {
				log.info("Billing notification {} for organization {} (mail not configured, not sent)", n.type(), n.organizationId());
				return;
			}
			SimpleMailMessage m = new SimpleMailMessage();
			m.setFrom(from);
			m.setTo(owner.getEmail());
			m.setSubject(content[0]);
			m.setText(content[1]);
			mail.send(m);
			log.info("Billing notification {} sent for organization {}", n.type(), n.organizationId());
		} catch (RuntimeException ex) {
			log.warn("Billing notification {} for organization {} failed: {}", n.type(), n.organizationId(), ex.getMessage());
		}
	}

	/** [subject, body] */
	String[] content(BillingNotification n, String practice) {
		String billing = props.frontendUrl().replaceAll("/+$", "") + "/app/billing";
		String date = n.date() == null ? "" : DATE.format(n.date());
		String money = n.amount() == null ? "" : format(n.amount(), n.currency());
		return switch (n.type()) {
			case TRIAL_ENDING -> new String[] {
					"Your Utessa free trial ends on " + date,
					"Hi,\n\nThe free trial for " + practice + " ends on " + date + ".\n\n"
							+ (n.hasPaymentMethod()
									? "Your subscription will start automatically and the card on file will be charged for the modules and seats you chose."
									: "There's no payment method on file yet. Add one before the trial ends to keep using Utessa without interruption.")
							+ "\n\nManage billing: " + billing + "\n\n— Utessa" };
			case PAYMENT_FAILED -> new String[] {
					"Payment failed for your Utessa subscription",
					"Hi,\n\nWe couldn't collect " + money + " for " + practice + ". Stripe will retry automatically.\n\n"
							+ "Please update your payment method" + (date.isEmpty() ? "" : " before " + date)
							+ " to avoid losing access. Your data is safe and won't be deleted.\n\n"
							+ (n.url() == null ? "" : "Pay the invoice: " + n.url() + "\n") + "Manage billing: " + billing + "\n\n— Utessa" };
			case CHANGE_PAYMENT_FAILED -> new String[] {
					"Your Utessa plan change wasn't completed",
					"Hi,\n\nThe payment of " + money + " for the plan change at " + practice + " didn't go through, so the change "
							+ "wasn't applied. Your current plan is unchanged.\n\n"
							+ (n.url() == null ? "" : "Complete the payment: " + n.url() + "\n") + "\n— Utessa" };
			case PAYMENT_RECOVERED -> new String[] {
					"Payment received — thank you",
					"Hi,\n\nWe've received the payment for " + practice + ". Your subscription is active again.\n\n— Utessa" };
			case SUBSCRIPTION_CANCELED -> new String[] {
					"Your Utessa subscription has ended",
					"Hi,\n\nThe subscription for " + practice + " has ended" + (date.isEmpty() ? "" : " (" + date + ")")
							+ ". Your data is kept, and you can subscribe again at any time.\n\nBilling: " + billing + "\n\n— Utessa" };
		};
	}

	private static String format(long minor, String currency) {
		try {
			Currency c = Currency.getInstance(currency == null ? "USD" : currency.toUpperCase());
			NumberFormat f = NumberFormat.getCurrencyInstance(Locale.US);
			f.setCurrency(c);
			return f.format(minor / Math.pow(10, Math.max(0, c.getDefaultFractionDigits())));
		} catch (RuntimeException ex) {
			return minor + " " + currency;
		}
	}

	@PreDestroy
	void shutdown() {
		executor.shutdown();
	}
}
