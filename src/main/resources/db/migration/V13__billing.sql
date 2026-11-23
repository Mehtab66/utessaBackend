-- Stripe subscription billing.
-- Money is stored as BIGINT in the currency's minor unit (cents) with an ISO-4217 currency code,
-- exactly as Stripe reports it. No card or bank details are ever stored: only Stripe IDs.
-- Statuses that Stripe owns (subscription / invoice status) are plain text because Stripe can add
-- new values; statuses this app owns use enum types like the rest of the schema.

CREATE TYPE billing_interval AS ENUM ('MONTHLY', 'YEARLY');

-- ACTIVE: billed now. SCHEDULED_ADD / SCHEDULED_REMOVAL: change takes effect at the next renewal
-- (subscription schedule). REMOVED: kept for history.
CREATE TYPE billing_item_status AS ENUM ('ACTIVE', 'SCHEDULED_ADD', 'SCHEDULED_REMOVAL', 'REMOVED');

CREATE TYPE billing_operation_type AS ENUM (
    'CREATE_CUSTOMER',
    'UPDATE_CUSTOMER',
    'CREATE_SUBSCRIPTION',
    'UPGRADE',
    'DOWNGRADE',
    'CHANGE_SEATS',
    'CHANGE_INTERVAL',
    'CANCEL',
    'REACTIVATE',
    'PAYMENT'
);

-- PENDING → PROCESSING → SUCCEEDED / FAILED / UNKNOWN (outcome not known, e.g. timeout; resolved later)
CREATE TYPE billing_operation_status AS ENUM ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN');

CREATE TYPE billing_webhook_status AS ENUM ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'IGNORED');

-- ── A. One billing record per organization ─────────────────────────────────────────
CREATE TABLE organization_subscriptions (
    id                      UUID PRIMARY KEY,
    organization_id         UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    stripe_customer_id      VARCHAR(255),
    stripe_subscription_id  VARCHAR(255),
    -- Subscription schedule holding a change booked for the next renewal (downgrade / yearly→monthly)
    stripe_schedule_id      VARCHAR(255),
    -- Stripe's status in lower case (trialing, active, past_due, unpaid, canceled, incomplete,
    -- incomplete_expired, paused) or 'none' while there is no subscription yet.
    subscription_status     VARCHAR(40) NOT NULL DEFAULT 'none',
    billing_interval        billing_interval,
    trial_start             TIMESTAMPTZ,
    trial_end               TIMESTAMPTZ,
    current_period_start    TIMESTAMPTZ,
    current_period_end      TIMESTAMPTZ,
    cancel_at_period_end    BOOLEAN NOT NULL DEFAULT FALSE,
    cancel_at               TIMESTAMPTZ,
    canceled_at             TIMESTAMPTZ,
    ended_at                TIMESTAMPTZ,
    -- Change booked for the next renewal
    scheduled_interval      billing_interval,
    scheduled_change_at     TIMESTAMPTZ,
    -- Failed-payment tracking: grace period starts at the first failure, cleared when an invoice is paid
    payment_failed_at       TIMESTAMPTZ,
    grace_period_ends_at    TIMESTAMPTZ,
    latest_invoice_id       VARCHAR(255),
    latest_invoice_status   VARCHAR(40),
    -- Stripe 'created' time of the newest object state applied; older webhook data is never applied over newer
    stripe_synced_at        TIMESTAMPTZ,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_org_subscriptions_org UNIQUE (organization_id),
    CONSTRAINT uq_org_subscriptions_customer UNIQUE (stripe_customer_id),
    CONSTRAINT uq_org_subscriptions_subscription UNIQUE (stripe_subscription_id)
);

CREATE INDEX idx_org_subscriptions_status ON organization_subscriptions(subscription_status);
CREATE INDEX idx_org_subscriptions_grace ON organization_subscriptions(grace_period_ends_at)
    WHERE grace_period_ends_at IS NOT NULL;

-- ── B. Line items on the subscription (one per module, one for team-member seats) ──────
CREATE TABLE subscription_items (
    id                           UUID PRIMARY KEY,
    subscription_id              UUID NOT NULL REFERENCES organization_subscriptions(id) ON DELETE CASCADE,
    -- crm | ops | mkt | tc | seat
    module_key                   VARCHAR(20) NOT NULL,
    stripe_subscription_item_id  VARCHAR(255),
    stripe_price_id              VARCHAR(255) NOT NULL,
    quantity                     INTEGER NOT NULL,
    billing_interval             billing_interval NOT NULL,
    unit_amount                  BIGINT NOT NULL,
    currency                     VARCHAR(3) NOT NULL,
    status                       billing_item_status NOT NULL DEFAULT 'ACTIVE',
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_subscription_items_module CHECK (module_key IN ('crm', 'ops', 'mkt', 'tc', 'seat')),
    CONSTRAINT ck_subscription_items_quantity CHECK (quantity >= 0),
    CONSTRAINT ck_subscription_items_amount CHECK (unit_amount >= 0),
    CONSTRAINT uq_subscription_items_stripe UNIQUE (stripe_subscription_item_id)
);

-- At most one live row per module on a subscription
CREATE UNIQUE INDEX uq_subscription_items_live_module
    ON subscription_items(subscription_id, module_key) WHERE status = 'ACTIVE';
CREATE INDEX idx_subscription_items_subscription ON subscription_items(subscription_id);

-- ── C. Invoices mirrored from Stripe (webhooks) ───────────────────────────────────────
CREATE TABLE subscription_invoices (
    id                      UUID PRIMARY KEY,
    organization_id         UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    subscription_id         UUID REFERENCES organization_subscriptions(id) ON DELETE SET NULL,
    stripe_invoice_id       VARCHAR(255) NOT NULL,
    stripe_subscription_id  VARCHAR(255),
    invoice_number          VARCHAR(100),
    billing_reason          VARCHAR(50),
    amount_due              BIGINT NOT NULL DEFAULT 0,
    amount_paid             BIGINT NOT NULL DEFAULT 0,
    amount_remaining        BIGINT NOT NULL DEFAULT 0,
    currency                VARCHAR(3) NOT NULL,
    -- Stripe's status: draft, open, paid, uncollectible, void
    invoice_status          VARCHAR(40) NOT NULL,
    hosted_invoice_url      TEXT,
    invoice_pdf_url         TEXT,
    period_start            TIMESTAMPTZ,
    period_end              TIMESTAMPTZ,
    paid_at                 TIMESTAMPTZ,
    attempt_count           INTEGER NOT NULL DEFAULT 0,
    next_payment_attempt    TIMESTAMPTZ,
    stripe_created_at       TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_subscription_invoices_stripe UNIQUE (stripe_invoice_id)
);

CREATE INDEX idx_subscription_invoices_org ON subscription_invoices(organization_id, stripe_created_at DESC);

-- ── D. Webhook events received (deduplication + processing status) ───────────────────
-- Only identifiers and status are kept, not the event payload (it can contain billing contact details).
CREATE TABLE stripe_webhook_events (
    id                 UUID PRIMARY KEY,
    stripe_event_id    VARCHAR(255) NOT NULL,
    event_type         VARCHAR(100) NOT NULL,
    stripe_object_id   VARCHAR(255),
    organization_id    UUID REFERENCES organizations(id) ON DELETE SET NULL,
    livemode           BOOLEAN NOT NULL DEFAULT FALSE,
    stripe_created_at  TIMESTAMPTZ,
    processing_status  billing_webhook_status NOT NULL DEFAULT 'RECEIVED',
    attempts           INTEGER NOT NULL DEFAULT 0,
    processed_at       TIMESTAMPTZ,
    error_message      TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_stripe_webhook_events_event UNIQUE (stripe_event_id)
);

CREATE INDEX idx_stripe_webhook_events_status ON stripe_webhook_events(processing_status, created_at);

-- ── E. Idempotency records for every billing operation that changes Stripe ────────────
CREATE TABLE stripe_idempotency_records (
    id                     UUID PRIMARY KEY,
    -- Key sent by the client (Idempotency-Key header); unique per organization
    idempotency_key        VARCHAR(255) NOT NULL,
    organization_id        UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    operation_type         billing_operation_type NOT NULL,
    -- SHA-256 of the normalized request; the same key can't be reused for a different request
    request_hash           VARCHAR(64) NOT NULL,
    -- Key actually sent to Stripe (namespaced by organization and operation)
    stripe_idempotency_key VARCHAR(255) NOT NULL,
    processing_status      billing_operation_status NOT NULL DEFAULT 'PENDING',
    -- Stripe object the operation produced (checkout session, subscription, schedule...)
    response_reference     VARCHAR(255),
    -- Saved API response so a retry with the same key returns the same answer
    response_body          TEXT,
    error_code             VARCHAR(100),
    error_message          TEXT,
    attempts               INTEGER NOT NULL DEFAULT 0,
    -- Lease: a PROCESSING record whose lease expired was interrupted and may be resumed
    locked_until           TIMESTAMPTZ,
    actor_user_id          UUID REFERENCES users(id) ON DELETE SET NULL,
    completed_at           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_idempotency_org_key UNIQUE (organization_id, idempotency_key),
    CONSTRAINT uq_idempotency_stripe_key UNIQUE (stripe_idempotency_key)
);

-- Only one billing change may be in flight per organization at a time
CREATE UNIQUE INDEX uq_idempotency_one_in_flight
    ON stripe_idempotency_records(organization_id) WHERE processing_status IN ('PENDING', 'PROCESSING');
CREATE INDEX idx_idempotency_unknown ON stripe_idempotency_records(processing_status, updated_at)
    WHERE processing_status = 'UNKNOWN';
