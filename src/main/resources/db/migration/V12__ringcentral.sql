-- =====================================================================
-- V12 · RingCentral phone system
--  * crm_rc_connections : each staff member's RingCentral sign-in
--    (OAuth tokens are stored encrypted, never returned by the API)
--  * crm_rc_calls       : mirror of the RingCentral call log, used for
--    the call log view, missed-call handling and the auto-text reply
--  * crm_messages.external_id : RingCentral message id, so inbound and
--    outbound texts are not imported twice
-- =====================================================================

CREATE TABLE crm_rc_connections (
    id                       UUID PRIMARY KEY,
    organization_id          UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id                  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    access_token_enc         TEXT NOT NULL,
    refresh_token_enc        TEXT,
    access_expires_at        TIMESTAMPTZ,
    refresh_expires_at       TIMESTAMPTZ,
    rc_account_id            VARCHAR(64),
    rc_extension_id          VARCHAR(64),
    extension_number         VARCHAR(20),
    extension_name           VARCHAR(150),
    caller_id_number         VARCHAR(30),
    sms_number               VARCHAR(30),
    phone_numbers_json       JSONB NOT NULL DEFAULT '[]'::jsonb,
    status                   VARCHAR(20) NOT NULL DEFAULT 'CONNECTED',  -- CONNECTED | ERROR
    last_error               VARCHAR(500),
    last_sync_at             TIMESTAMPTZ,
    last_message_sync_at     TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_crm_rc_connections_user ON crm_rc_connections(organization_id, user_id);

CREATE TABLE crm_rc_calls (
    id                       UUID PRIMARY KEY,
    organization_id          UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    rc_record_id             VARCHAR(100) NOT NULL,
    telephony_session_id     VARCHAR(128),
    session_id               VARCHAR(128),
    connection_user_id       UUID REFERENCES users(id) ON DELETE SET NULL,
    direction                VARCHAR(10) NOT NULL,           -- INBOUND | OUTBOUND
    result                   VARCHAR(60),
    missed                   BOOLEAN NOT NULL DEFAULT FALSE,
    from_number              VARCHAR(40),
    from_name                VARCHAR(150),
    to_number                VARCHAR(40),
    to_name                  VARCHAR(150),
    phone_normalized         VARCHAR(20),
    patient_id               UUID REFERENCES crm_patients(id) ON DELETE SET NULL,
    started_at               TIMESTAMPTZ NOT NULL,
    duration_seconds         INTEGER,
    has_recording            BOOLEAN NOT NULL DEFAULT FALSE,
    activity_id              UUID,
    auto_text_status         VARCHAR(20),                     -- SENT | SKIPPED | FAILED
    auto_text_note           VARCHAR(255),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_crm_rc_calls_record ON crm_rc_calls(organization_id, rc_record_id);
CREATE INDEX idx_crm_rc_calls_org ON crm_rc_calls(organization_id, started_at DESC);
CREATE INDEX idx_crm_rc_calls_session ON crm_rc_calls(organization_id, telephony_session_id);

ALTER TABLE crm_messages ADD COLUMN external_id VARCHAR(100);
CREATE INDEX idx_crm_messages_external ON crm_messages(organization_id, external_id);
