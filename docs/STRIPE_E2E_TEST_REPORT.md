# Stripe billing — end-to-end test report (2026-11-24)

Run against the local backend (8080) + `stripe listen`, Stripe **sandbox**, using test clocks.
Three throwaway practices were created (logins in git-ignored `scripts/.env.billing-test`, keys `BILLING_E2E*`):

| Practice | Org id | Test clock | Used for |
|---|---|---|---|
| E2E Trial Practice | `75785098-75a3-4e0e-9a04-01bc585caf69` | `clock_1UfDms1TcpCBmAto3loAtc5C` | trial → ended → bad cards → subscribe → renewals → no-money renewal → grace → recovery → retries exhausted |
| E2E Changes Practice | `7f071c8a-4554-465d-bd2f-d8e9cde3add6` | `clock_1UfEBo1TcpCBmAtoyz9lkZRz` | upgrades, seats, failed upgrade, downgrades, cancel / reactivate / ended, re-subscribe |
| E2E Cycle Practice | `ddaa0b41-d596-451c-bfbb-9df1ad329b57` | `clock_1UfEOK1TcpCBmAtoOKKRloEd` | monthly → yearly, yearly → monthly |

Subscriptions were created directly in Stripe (same prices, same metadata as Checkout) because the hidden browser
pane couldn't drive the Checkout page reliably; everything after that went through the app's own API and webhooks.

## Results

| # | Scenario | Result |
|---|---|---|
| 1 | New practice on trial | ✅ `TRIAL`, all 4 modules open, 10 member cap |
| 2 | Trial ends without subscribing | ✅ `INACTIVE`, every module 402 "Your free trial has ended…", billing/dashboard still open |
| 3 | Wrong / no-money card saved | ✅ Stripe refuses to save it: declined, **insufficient funds**, expired, wrong CVC — nothing created |
| 4 | Card saves but first charge fails | ✅ subscription `incomplete` → no access; after 23 h `incomplete_expired`; new Checkout still allowed |
| 5 | Abandoned Checkout | ✅ session expires, practice stays `INACTIVE` |
| 6 | Subscribe with good card | ✅ `ACTIVE`, only paid modules open (others 402 "isn't part of your plan") |
| 7 | Subscribing during the trial | ✅ trial ends at once (`trialActive=false`) |
| 8 | Monthly renewal, good card | ✅ invoice paid, period moves forward |
| 9 | Renewal, card has no money | ✅ `past_due`, `invoice.payment_failed`, overdue flag, open invoice with Pay link |
| 10 | Grace period running | ✅ `PAST_DUE`, paid modules still open |
| 11 | Grace period over | ✅ `RESTRICTED`, modules 402 "payment is overdue… your data is safe", billing open |
| 12 | Stripe automatic retry fails | ✅ attempt 2 recorded, still restricted |
| 13 | Customer updates card and pays | ✅ back to `ACTIVE`, overdue + grace cleared, modules open |
| 14 | All retries fail | ✅ after 3 attempts (~2 weeks) Stripe **cancels** the subscription → `INACTIVE` |
| 15 | Upgrade now (add module), good card | ✅ previewed $25 → charged $25, module opens |
| 16 | Add seats now | ✅ previewed $30 → charged $30, seat cap 5 |
| 17 | Upgrade with card that fails | ⚠️ change correctly **not** applied, not marked overdue — but see bug B |
| 18 | Unpaid upgrade after 23 h | ✅ pending change dropped (invoice voided — see bug C) |
| 19 | Downgrade at renewal + undo | ✅ booked, shown as scheduled removal, undo works |
| 20 | Two downgrades booked for renewal | ❌ bug A |
| 21 | Cancel at period end / reactivate | ✅ access until period end; reactivate clears it |
| 22 | Cancelled period runs out | ✅ `INACTIVE`, 402 "Your subscription has ended…" |
| 23 | Re-subscribe after it ended | ✅ no trial (even with a future trial date), charged at Checkout, same Stripe customer |
| 24 | Monthly → yearly now | ✅ $360 prorated charge, items yearly |
| 25 | Yearly → monthly at renewal | ✅ applied at the yearly renewal, $40 monthly charged |
| — | Webhooks | ✅ every event `PROCESSED`, none `FAILED` |

## Bugs found

**A. A second change booked for renewal wipes out the first** (important, overcharges). ✅ **Fixed** — new renewal changes build on the booked plan; Billing modal starts from it; verified: remove module → seats 5→3 → 3→2 all kept, renewal charged $50.
Booked "remove Marketing at renewal", then "seats 5 → 3 at renewal". The second booking rebuilt the schedule from the
*current* plan, so Marketing was put back. At renewal the customer was charged **$85 instead of $55** and kept Marketing.
Fix: when a schedule already exists, plan the new change on top of the scheduled target, not the current items
(`SubscriptionChangeService` / `ChangePlanner`).

**B. Failed upgrade shows "Your plan has been updated."** (important, misleading). ✅ **Fixed** — invoice re-read for its payment link (verified: link returned); modal says the payment didn't go through and nothing changed.
`/upgrade` returns `PAYMENT_REQUIRED` with `paymentUrl: null` — the `latest_invoice` expanded on the update response has no
`hosted_invoice_url` yet. `PricingModal.jsx:148` only handles `PAYMENT_REQUIRED` when a URL is present, so it falls through
to "Your plan has been updated." Fix: re-fetch the invoice when the URL is missing, and have the frontend handle
`PAYMENT_REQUIRED` without a URL ("Payment failed — nothing changed; update your card in Billing").

**C. Voided invoices stay "open" in the app with a Pay link.** ✅ **Fixed** — `invoice.finalized/voided/marked_uncollectible` handled; Refresh also re-reads the last 24 invoices (repairs old rows). Add these 3 events to the Dashboard webhook and to any `stripe listen --events` list. `invoice.voided` (and `invoice.marked_uncollectible`,
`invoice.updated`) aren't handled, so voided invoices (expired first payment, expired unpaid upgrade) keep their old status.

**D. Preview returns 500 on a test clock.** ✅ **Fixed** — proration date uses the test clock's time; Stripe errors on previews/reads now return their status with a readable message (no 500). Proration date is the real time; once a test clock is ahead of real time,
Stripe rejects it and the app answers a generic 500. Only affects test clocks in practice, but it should answer a clear
error, and could use the clock's time (same root cause as the grace period counting real days).

**E. `scheduledChangeAt` stays set after the change has happened** (minor) ✅ **Fixed** — booked change read from the schedule's current phase, not the server clock. — the API keeps returning the past date.

**F. Invoice period shows the previous period** for renewal invoices (minor) ✅ **Fixed** — period taken from the invoice lines. — uses the invoice's `period_start/end`, which
Stripe sets to the period *before* the invoice; the line item periods are the right ones to show.

**G. Upgrades blocked for a month after a booked change applied** (important). Stripe keeps the schedule attached
through its last phase, and the app refused every immediate change with "A change is already booked". ✅ **Fixed** — only a
change still waiting blocks; a finished schedule is released before an immediate change (verified).

## Stripe settings to decide on (Dashboard)

- **Checkout shows prices in PKR** (Adaptive Pricing) and charges in PKR at the exchange rate. Turn it off under
  Settings → Payments → Adaptive Pricing if customers should always pay USD.
- **Renewal invoices wait 72 h as drafts** before charging (Billing settings). Default is 1 h.
- **After all retries fail Stripe cancels the subscription** (3 attempts, ~2 weeks). The last invoice stays open with a
  Pay link; paying it then won't bring the subscription back. Consider "mark invoice uncollectible" / leaving it past-due.
- `.env` still has `BILLING_GRACE_PERIOD_DAYS=0` from testing — remove it (default 7).

## Not covered

- Stripe Checkout's own decline screens (they're Stripe's page; the same cards were tested by API in #3).
- Emails to the owner (mail delivery wasn't checked).
- The Billing page UI for these practices (checked through the API the page uses).

---

## Re-test after the fixes (2026-11-24, afternoon)

Three fresh practices (logins `BILLING_RT_P1/P2/P3` in `scripts/.env.billing-test`), scripted checks, run in parallel.

| Run | Covers | Result |
|---|---|---|
| P1 lifecycle | trial → trial over → 4 bad cards refused → failed first payment → expires (invoice shows void) → subscribe → renewal → no-money renewal → grace → restricted → retry → pays → active → all retries fail → cancelled | 39/39 ✅ (2 needed Refresh, see below) |
| P2 plan changes | upgrade, seats, failed upgrade (payment link), unpaid upgrade expires (void), upgrade with clock ahead, 3 stacked renewal bookings ($125 next), no-op/blocked messages, undo + rebook, renewal charged $125, booking cleared, upgrade right after, cancel/reactivate/end, re-subscribe without trial | 42/42 ✅ (2 needed Refresh) |
| P3 cycle | monthly → yearly now, yearly → monthly at renewal, seats on top of the booking, year later monthly $40 | 10/10 ✅ |
| Billing modal (browser) | failed seat upgrade → "The payment didn't go through, so nothing has changed yet… Complete payment"; plan unchanged | ✅ |

**Missed events:** while three test clocks advanced at once, Stripe didn't send ~10 events to the `stripe listen`
sessions at all (no delivery attempt in the CLI log), including two renewal `invoice.paid` events. Access and plans were
still right (subscription events arrived); the invoice list was missing those invoices until **Refresh**, which restored them
with correct amounts and periods. A Dashboard webhook endpoint retries for 3 days, so this is a CLI-testing limit, not an
app bug. Also: an invoice whose events arrive before the app has linked the Stripe customer (only possible when a
subscription is created outside the app's Checkout) is ignored until Refresh.

**Your `stripe listen`** is still started with `--events` for 6 events; restart it without `--events` (or add
`invoice.finalized,invoice.voided,invoice.marked_uncollectible,customer.subscription.trial_will_end`).
