package com.crm.crm_backend.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.stripe.model.Invoice;
import com.stripe.model.InvoiceLineItem;
import com.stripe.model.InvoiceLineItemCollection;

/** The period shown for an invoice is the one it charges for, not Stripe's invoice-level "previous period". */
class InvoicePeriodTest {

	private static final long NOV_25 = 1_795_590_000L;
	private static final long DEC_25 = NOV_25 + 30 * 86_400L;
	private static final long JAN_25 = DEC_25 + 31 * 86_400L;

	@Test
	void renewalInvoiceShowsTheNewPeriodFromItsLines() {
		// Stripe reports the renewal invoice's own period as the month before it.
		Invoice inv = invoice(NOV_25, DEC_25, line(DEC_25, JAN_25, false), line(DEC_25, JAN_25, false));

		assertThat(InvoiceSyncService.servicePeriod(inv)).containsExactly(DEC_25, JAN_25);
	}

	@Test
	void regularLinesWinOverProrationsFromEarlierChanges() {
		long midDec = NOV_25 + 20 * 86_400L;
		Invoice inv = invoice(NOV_25, DEC_25, line(midDec, DEC_25, true), line(DEC_25, JAN_25, false));

		assertThat(InvoiceSyncService.servicePeriod(inv)).containsExactly(DEC_25, JAN_25);
	}

	@Test
	void prorationOnlyInvoiceShowsTheProratedSpan() {
		long midDec = NOV_25 + 20 * 86_400L;
		Invoice inv = invoice(midDec, midDec, line(midDec, DEC_25, true));

		assertThat(InvoiceSyncService.servicePeriod(inv)).containsExactly(midDec, DEC_25);
	}

	@Test
	void noLinesFallsBackToTheInvoiceDates() {
		Invoice inv = invoice(NOV_25, DEC_25);

		assertThat(InvoiceSyncService.servicePeriod(inv)).containsExactly(NOV_25, DEC_25);
	}

	private static Invoice invoice(long start, long end, InvoiceLineItem... lines) {
		Invoice inv = new Invoice();
		inv.setPeriodStart(start);
		inv.setPeriodEnd(end);
		InvoiceLineItemCollection c = new InvoiceLineItemCollection();
		c.setData(List.of(lines));
		inv.setLines(c);
		return inv;
	}

	private static InvoiceLineItem line(long start, long end, boolean proration) {
		InvoiceLineItem l = new InvoiceLineItem();
		InvoiceLineItem.Period p = new InvoiceLineItem.Period();
		p.setStart(start);
		p.setEnd(end);
		l.setPeriod(p);
		InvoiceLineItem.Parent parent = new InvoiceLineItem.Parent();
		InvoiceLineItem.Parent.SubscriptionItemDetails d = new InvoiceLineItem.Parent.SubscriptionItemDetails();
		d.setProration(proration);
		parent.setSubscriptionItemDetails(d);
		parent.setType("subscription_item_details");
		l.setParent(parent);
		return l;
	}
}
