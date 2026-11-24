# Utessa Billing (Stripe)

Subscription billing for practices: four paid modules (CRM, Operations, Marketing, Time Clock) plus
per-member team seats, monthly or yearly, on **one Stripe subscription per organization**.

Code lives in `com.crm.crm_backend.billing` (`api` / `application` / `domain` / `infrastructure`),
tables in migrations `V13`–`V17`.

---

## 1. Business rules

| Rule | Behaviour |
|---|---|
| Free trial | 14 days from signup, **no card needed**. All 4 modules; owner + **10** team members. |
| Trial vs paid | Kept separate. **Subscribing ends the trial**: the first period is charged at checkout and the practice moves straight to its paid plan. The trial is then over for good (cancelling later doesn't bring it back). Without subscribing, the trial simply ends at its date. |
| Seats | Charged per team member **besides the owner** (owner is free). Seats can't go below the current team size. |
| One subscription | Each organization has exactly one Stripe subscription; changes update it by **subscription item id**. Never a second one. |
| Prices | Never hard-coded. Configured as Stripe Price or Product IDs; amounts come from Stripe. The browser only sends module keys, a seat count and an interval. |
| Upgrades | Applied now, prorated, charged immediately — only after an invoice **preview** the customer saw. If the payment fails, Stripe doesn't apply the change. |
| Downgrades | Default at the next renewal (subscription schedule); optional immediate with credit. |
| Monthly ↔ yearly | To yearly: now (prorated). To monthly: at renewal by default. Modules and seats are kept. |
| Failed renewal | Access continues for a **7-day grace period** (configurable), then modules are blocked until paid. Data is never deleted. |
| Cancellation | At period end (default, reversible with reactivate) or immediately (no refund). |

### What a practice can use (`EntitlementService`)

| State | Modules | Members (besides owner) |
|---|---|---|
| `TRIAL` | all 4 | `billing.trial-max-members` (10) |
| `ACTIVE` | modules on the subscription (a module booked for removal stays until renewal) | seats bought |
| `PAST_DUE` | same as paid, `paymentOverdue=true` | seats bought |
| `RESTRICTED` | none — grace period over / `unpaid` | — |
| `INACTIVE` | none — trial over without a plan, or cancelled | — |

Enforced on the server by `ModuleAccessInterceptor`: `/api/v1/crm/**` → CRM, `/api/v1/staff|vendors|providers|operations/**`
→ Operations, `/api/v1/marketing/**` → Marketing, `/api/v1/timeclock/**` → Time Clock. Not entitled → **HTTP 402** with a
human-readable `message`. Sign-in, billing, workspace, team, organization and dashboard routes are never blocked.

---

## 2. Configuration

All values come from `utessaBackend/.env` (git-ignored) through `application.properties` (`billing.*`).

| Variable | Purpose |
|---|---|
| `STRIPE_SECRET_KEY` | `sk_test_…` / `sk_live_…`. Never logged or returned to the browser. |
| `STRIPE_PUBLISHABLE_KEY` | `pk_…`, returned by `GET /billing/pricing`. |
| `STRIPE_WEBHOOK_SECRET` | `whsec_…` of the webhook endpoint. Empty → webhook answers 503 (Stripe retries). |
| `STRIPE_PRICE_{CRM,OPS,MKT,TC,SEAT}_{MONTHLY,YEARLY}` | Price ID (`price_…`) **or** Product ID (`prod_…` → its default price). Validated at startup: active, recurring, licensed, right interval, right currency. |
| `STRIPE_CURRENCY` | Default `usd`. |
| `BILLING_FRONTEND_URL` | Where Stripe Checkout returns (`/app/billing?checkout=success&session_id=…`). |
| `BILLING_TRIAL_MAX_MEMBERS` | Default 10. |
| `BILLING_GRACE_PERIOD_DAYS` | Default 7. |
| `BILLING_RECONCILE_INTERVAL_MS` | Recovery job interval, default 120000. |

The trial length is the existing `app.trial.days` (14).

---

## 3. API

All under `/api/v1/billing`, for the caller's own practice (from the session — never an id in the request).
**Owner/admin** only, except `pricing` and `entitlements` (any member).

Every request that changes billing needs an **`Idempotency-Key`** header (8–100 chars: letters, digits, `- _ : .`).
Generate a new key (e.g. a UUID) for each new action the user takes, and **reuse the same key when retrying**
that action (timeouts, network errors, double clicks). Same key + same request → same answer, charged once.
Same key + different request → 422.

| Method & path | Body | Returns |
|---|---|---|
| `GET /pricing` | — | currency, trial days, trial member cap, publishable key, monthly/yearly price per module and per seat (minor units) |
| `GET /entitlements` | — | `state`, `modules`, `maxMembers`, `members`, `seatsLeft`, `trialEndsAt`, `paymentOverdue`, `gracePeriodEndsAt`, `cancellationScheduled`, `accessEndsAt` |
| `GET /subscription` | — | status, interval, trial, period, items (incl. `SCHEDULED_ADD` / `SCHEDULED_REMOVAL`), seats purchased/used, scheduled change, failure/grace dates |
| `GET /invoices?limit=24` | — | invoices (status, amounts, period, hosted URL, PDF URL) |
| `GET /customer`, `POST /customer`, `PUT /customer` | `{name, email, phone, address{line1,line2,city,state,postalCode,country}}` | billing details shown on invoices |
| `POST /subscriptions` | `{"modules":["crm","ops"],"seats":5,"interval":"MONTHLY"}` | `{sessionId, checkoutUrl, expiresAt}` — redirect to `checkoutUrl`; the first period is charged there (`trialEndsAt` is always null) |
| `GET /checkout/{sessionId}` | — | `{status: open/complete/expired, subscription}` — call from the success page; reads Stripe server-side |
| `POST /preview` | `{"modules":[…],"seats":8,"interval":"MONTHLY","timing":"IMMEDIATE"}` (omitted = unchanged) | `changeType`, `timing`, `amountDueNow`, `nextInvoiceAmount/At`, `lines`, `previewToken` (15 min) |
| `POST /upgrade` | change + `previewToken` | `{result: APPLIED / PAYMENT_REQUIRED, amountInvoiced, paymentUrl, subscription}` |
| `POST /downgrade` | change (`timing` default `PERIOD_END`) | `{result: SCHEDULED / APPLIED, effectiveAt, subscription}` |
| `POST /team-members` | `{"seats":8,"previewToken":…}` | as above |
| `POST /change-billing-cycle` | `{"interval":"YEARLY","timing":…,"previewToken":…}` | as above |
| `POST /scheduled-change/cancel` | — | undoes a change booked for renewal |
| `POST /cancel` | `{"mode":"PERIOD_END"}` or `{"mode":"IMMEDIATE"}` | `{result: CANCEL_SCHEDULED / CANCELED, effectiveAt}` |
| `POST /reactivate` | — | `{result: REACTIVATED}` |
| `POST /subscription/refresh` | — | re-reads the subscription and the last 24 invoices from Stripe (recovers missed webhooks) |

Webhook: `POST /api/v1/stripe/webhook` (public, signature-verified).

### Typical frontend flows

**Subscribe** → `GET /pricing` → user picks modules/seats/interval → `POST /subscriptions` → redirect to `checkoutUrl`
→ Stripe returns to `/app/billing?checkout=success&session_id=…` → `GET /checkout/{sessionId}` → show the subscription.
Never treat the redirect itself as payment.

**Change plan** → `POST /preview` → show `amountDueNow` and the lines → on confirm send the same change with
`previewToken` to `/upgrade`, `/downgrade`, `/team-members` or `/change-billing-cycle`. `409 "preview again"` means the
subscription changed or the preview expired. `PAYMENT_REQUIRED` → send the user to `paymentUrl`.

**Module gate** → `GET /entitlements` to decide what to show; still handle **402** from any module API.

---

## 4. How it stays correct

**Idempotency** (`IdempotencyService`, table `stripe_idempotency_records`)
1. *Claim* (short transaction + per-organization PostgreSQL advisory lock): look up the key; replay a finished
   result; refuse a key reused for another request (422) or a second concurrent change (409); else insert `PROCESSING`.
2. *Work* (no transaction open): Stripe calls, each with a key derived from the stored Stripe key, so a retry sends
   Stripe the **same** idempotency keys.
3. *Finish*: `SUCCEEDED` (response saved for replay) / `FAILED` (Stripe definitely rejected) / `UNKNOWN`
   (timeout, connection drop, 5xx, crash after the Stripe call). **A timeout is never treated as a failure.**
   A partial unique index allows only one in-flight change per organization.

**Recovery job** (`BillingReconciliationJob`, every 2 min): settles `UNKNOWN` and interrupted operations by checking
Stripe (customer exists? checkout created? are the requested modules/seats/interval in effect or booked?). Found →
`SUCCEEDED`; not found → `FAILED` ("didn't go through, nothing charged"). Unverifiable after 23 h (Stripe's idempotency
window) → `FAILED`. While unresolved, other changes for that practice get 409; retrying the same key resumes safely.

**Webhooks** (`StripeWebhookService`): signature verified with Stripe's library (5-minute tolerance) → event id
stored once (unique) → handler **re-reads the object from Stripe** (never trusts the payload), so duplicates and
out-of-order delivery can't corrupt state → `PROCESSED` / `IGNORED` / `FAILED` (500 → Stripe retries; plus
`StripeWebhookRetryJob` every 5 min). Handled: `customer.subscription.created|updated|deleted|trial_will_end`,
`invoice.paid`, `invoice.payment_failed`, `invoice.finalized|updated|voided|marked_uncollectible`, `checkout.session.completed`. A second live subscription for an
organization is cancelled immediately and logged for refund review.

**Concurrency**: one in-flight change per organization (index + advisory lock), row lock + `@Version` on the
subscription row, no DB transaction held during Stripe calls.

**Security**: owner/admin permission (`billing`) for changes; organization from the session only; server-side price
mapping; signed preview tokens (HMAC, org + change + subscription state + proration date); no card data stored;
logs contain Stripe ids and request ids only.

**Notifications** (`BillingNotification` → `BillingEmailNotifier`, emails the owner off-thread): trial ending,
payment failed (with grace date), plan-change payment failed, payment recovered, subscription ended.

---

## 5. Setting up Stripe

### Local development
1. Fill the `STRIPE_*` values in `.env` (test keys).
2. Webhooks: install the [Stripe CLI](https://docs.stripe.com/stripe-cli) and run
   `stripe listen --forward-to localhost:8080/api/v1/stripe/webhook`; put the printed `whsec_…` in `STRIPE_WEBHOOK_SECRET`.

### Production checklist
- [ ] Live keys (`sk_live_…`, `pk_live_…`) and live price/product IDs in the server environment (not in git).
- [ ] Dashboard → Developers → Webhooks → add `https://<api-host>/api/v1/stripe/webhook` with the 11 events above;
      set its signing secret in `STRIPE_WEBHOOK_SECRET`.
- [ ] Dashboard → Billing → Subscriptions and emails → enable **Smart Retries** for failed payments; decide on
      Stripe's own customer emails (Utessa also emails the owner).
- [ ] `BILLING_FRONTEND_URL` = the production app URL.
- [ ] HTTPS in front of the API; set the session cookie `secure` flag (`SessionCookieService`).
- [ ] Tax: if required, enable Stripe Tax and add `automatic_tax` to Checkout / subscription updates.
- [ ] Roll the test keys that were shared during development.

---

## 6. Operations

| Situation | Where to look / what to do |
|---|---|
| A change is stuck | `stripe_idempotency_records` where `processing_status = 'UNKNOWN'`; the recovery job settles it within minutes; or the user retries with the same key. |
| A webhook failed | `stripe_webhook_events` where `processing_status = 'FAILED'` (`error_message`); retried automatically up to 10 times. |
| Local state looks wrong | `POST /api/v1/billing/subscription/refresh` as the practice owner, or re-send the event from the Stripe Dashboard. |
| Duplicate subscription in logs | It was cancelled automatically; check in Stripe whether its first invoice needs a refund. |
| Seat limit for invites | Call `EntitlementService.requireSeatForNewMember(orgId)` in any add-member / invite flow (none exists yet). |

---

## 7. Tests

```
CREATE DATABASE utessa_crm_test;   -- once
./mvnw test
```

Profile `test` (`src/test/resources/application-test.properties`) uses `utessa_crm_test`, mocks the two Stripe-facing
classes (`StripeGateway`, `PricingCatalog`) and never calls Stripe.

| Test | Covers |
|---|---|
| `ChangePlannerTest` | upgrade/downgrade/mixed/interval planning; existing items updated by id |
| `EntitlementServiceTest` | trial, paid, scheduled removal, grace → restricted, trial-ends-for-good, cancellation, seat limit |
| `PreviewTokensTest` | signed preview tokens: tampering, other org/change, expiry, subscription changed |
| `StripeCallExceptionTest` | timeouts/5xx are UNKNOWN, declines are REJECTED, rate limits retryable |
| `ModuleAccessInterceptorTest` | route → module mapping |
| `IdempotencyIntegrationTest` | concurrent same key → one Stripe call; concurrent different keys → one change; replay; 422; UNKNOWN → resume with the same Stripe key; FAILED replay |
| `StripeWebhookIntegrationTest` | signature checks, dedup, state from Stripe not payload, late events, failed renewal → grace → recovery, live/test mode mismatch |
| `ModuleAccessIntegrationTest` | HTTP 402 for unpaid modules; billing always reachable; trial allows all |

End-to-end checks against Stripe test mode (Checkout, prorations, schedules, a declining test card, and a test
clock for failed renewals) were also run manually during development.
