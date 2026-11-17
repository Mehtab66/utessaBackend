-- =====================================================================
-- V9 · Phone workspace: texts, voicemail, fax, phone configuration
-- Outbound texts and faxes are stored as QUEUED until a phone-system
-- connector is live; inbound items arrive from that connector (or are
-- logged by hand from a desk phone / fax machine).
-- =====================================================================

CREATE TABLE crm_messages (
    id                UUID PRIMARY KEY,
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    patient_id        UUID REFERENCES crm_patients(id) ON DELETE SET NULL,
    phone             VARCHAR(50) NOT NULL,
    phone_normalized  VARCHAR(20) NOT NULL,
    contact_name      VARCHAR(150),
    direction         VARCHAR(10) NOT NULL,               -- OUT | IN
    body              TEXT NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'QUEUED', -- QUEUED | SENT | DELIVERED | FAILED | RECEIVED
    line              VARCHAR(40),
    sent_by_user_id   UUID REFERENCES users(id) ON DELETE SET NULL,
    sent_by_name      VARCHAR(150),
    read_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_crm_messages_thread ON crm_messages(organization_id, phone_normalized, created_at);

CREATE TABLE crm_voicemails (
    id                 UUID PRIMARY KEY,
    organization_id    UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    patient_id         UUID REFERENCES crm_patients(id) ON DELETE SET NULL,
    from_phone         VARCHAR(50) NOT NULL,
    phone_normalized   VARCHAR(20),
    caller_name        VARCHAR(150),
    mailbox            VARCHAR(60) NOT NULL DEFAULT 'Main line',
    duration_seconds   INTEGER,
    transcript         TEXT,
    recording_url      VARCHAR(500),
    received_at        TIMESTAMPTZ NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'NEW',   -- NEW | HEARD | HANDLED
    handled_by_name    VARCHAR(150),
    handled_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_crm_voicemails_org ON crm_voicemails(organization_id, status, received_at DESC);

CREATE TABLE crm_faxes (
    id                UUID PRIMARY KEY,
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    direction         VARCHAR(10) NOT NULL,                -- IN | OUT
    fax_number        VARCHAR(50) NOT NULL,
    party_name        VARCHAR(150),
    patient_id        UUID REFERENCES crm_patients(id) ON DELETE SET NULL,
    subject           VARCHAR(255),
    document_type     VARCHAR(60),
    pages             INTEGER,
    cover_page        BOOLEAN NOT NULL DEFAULT FALSE,
    cover_note        TEXT,
    status            VARCHAR(20) NOT NULL,                -- QUEUED | SENT | FAILED | RECEIVED | FILED
    file_name         VARCHAR(255),
    content_type      VARCHAR(100),
    file_size         INTEGER,
    file_data         BYTEA,
    filed_by_name     VARCHAR(150),
    filed_at          TIMESTAMPTZ,
    created_by_name   VARCHAR(150),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_crm_faxes_org ON crm_faxes(organization_id, direction, created_at DESC);

-- Practice-wide call handling (user_id NULL) and each user's own phone preferences.
CREATE TABLE crm_phone_config (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id          UUID REFERENCES users(id) ON DELETE CASCADE,
    config_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_crm_phone_config_practice ON crm_phone_config(organization_id) WHERE user_id IS NULL;
CREATE UNIQUE INDEX uq_crm_phone_config_user ON crm_phone_config(organization_id, user_id) WHERE user_id IS NOT NULL;
