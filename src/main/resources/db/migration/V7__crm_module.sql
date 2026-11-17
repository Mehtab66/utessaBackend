-- =====================================================================
-- V7 · Healthcare CRM module
--  * patient_leads is promoted to crm_patients (one person record that moves
--    through the lifecycle LEAD -> PATIENT), so caller lookup, timeline,
--    tasks and appointments all hang off a single id.
--  * Adds referral partners, referrals, opportunities, appointments, tasks,
--    activities (timeline), targets/quotas and integration settings
--    (phone system + EHR).
-- =====================================================================

CREATE TYPE crm_lifecycle AS ENUM ('LEAD', 'PATIENT', 'INACTIVE');

CREATE TYPE crm_lead_status AS ENUM (
    'NEW',
    'ATTEMPTING_CONTACT',
    'CONTACTED',
    'QUALIFIED',
    'APPOINTMENT_SCHEDULED',
    'SHOWED',
    'CONVERTED',
    'NOT_QUALIFIED',
    'LOST'
);

CREATE TYPE crm_priority AS ENUM ('LOW', 'MEDIUM', 'HIGH', 'URGENT');

CREATE TYPE crm_referral_status AS ENUM (
    'RECEIVED',
    'IN_REVIEW',
    'INSURANCE_VERIFICATION',
    'AUTHORIZATION_PENDING',
    'SCHEDULED',
    'COMPLETED',
    'DECLINED',
    'CANCELLED'
);

CREATE TYPE crm_opportunity_stage AS ENUM (
    'INQUIRY',
    'QUALIFIED',
    'CONSULT_SCHEDULED',
    'CONSULT_COMPLETED',
    'TREATMENT_PRESENTED',
    'WON',
    'LOST'
);

CREATE TYPE crm_appointment_status AS ENUM (
    'SCHEDULED',
    'CONFIRMED',
    'CHECKED_IN',
    'COMPLETED',
    'NO_SHOW',
    'CANCELLED',
    'RESCHEDULED'
);

CREATE TYPE crm_task_status AS ENUM ('OPEN', 'IN_PROGRESS', 'DONE', 'CANCELLED');

CREATE TYPE crm_activity_type AS ENUM (
    'CALL_INBOUND',
    'CALL_OUTBOUND',
    'NOTE',
    'SMS',
    'EMAIL',
    'MEETING',
    'STATUS_CHANGE',
    'SYSTEM'
);

-- ---------------------------------------------------------------------
-- Referral partners (referring physicians, practices, facilities)
-- ---------------------------------------------------------------------
CREATE TABLE crm_referral_partners (
    id                   UUID PRIMARY KEY,
    organization_id      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name                 VARCHAR(255) NOT NULL,
    partner_type         VARCHAR(50)  NOT NULL DEFAULT 'PHYSICIAN',
    relationship_status  VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    npi                  VARCHAR(20),
    specialty            VARCHAR(150),
    contact_name         VARCHAR(150),
    phone                VARCHAR(50),
    fax                  VARCHAR(50),
    email                VARCHAR(255),
    street               VARCHAR(255),
    city                 VARCHAR(100),
    state                VARCHAR(50),
    zip                  VARCHAR(20),
    owner_user_id        UUID REFERENCES users(id) ON DELETE SET NULL,
    owner_name           VARCHAR(150),
    last_touch_at        TIMESTAMPTZ,
    notes                TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_crm_partners_org ON crm_referral_partners(organization_id);

-- ---------------------------------------------------------------------
-- Patients (leads + patients) — promoted from patient_leads
-- ---------------------------------------------------------------------
ALTER TABLE patient_leads RENAME TO crm_patients;
ALTER INDEX idx_leads_org RENAME TO idx_crm_patients_org;
DROP INDEX IF EXISTS idx_leads_status;

ALTER TABLE crm_patients
    ADD COLUMN lead_status crm_lead_status NOT NULL DEFAULT 'NEW',
    ADD COLUMN lifecycle_stage crm_lifecycle NOT NULL DEFAULT 'LEAD',
    ADD COLUMN priority crm_priority NOT NULL DEFAULT 'MEDIUM';

UPDATE crm_patients SET lead_status = CASE status::text
        WHEN 'NEW' THEN 'NEW'
        WHEN 'CONTACTED' THEN 'CONTACTED'
        WHEN 'SCHEDULED' THEN 'APPOINTMENT_SCHEDULED'
        WHEN 'SHOWED' THEN 'SHOWED'
        WHEN 'CONVERTED' THEN 'CONVERTED'
        ELSE 'LOST'
    END::crm_lead_status;

UPDATE crm_patients SET lifecycle_stage = 'PATIENT' WHERE lead_status = 'CONVERTED';

ALTER TABLE crm_patients DROP COLUMN status;
DROP TYPE lead_status;

ALTER TABLE crm_patients RENAME COLUMN referral_source_detail TO source_detail;

ALTER TABLE crm_patients
    ADD COLUMN first_name            VARCHAR(100),
    ADD COLUMN last_name             VARCHAR(100),
    ADD COLUMN preferred_name        VARCHAR(100),
    ADD COLUMN date_of_birth         DATE,
    ADD COLUMN gender                VARCHAR(30),
    ADD COLUMN phone_normalized      VARCHAR(20),
    ADD COLUMN alt_phone             VARCHAR(50),
    ADD COLUMN alt_phone_normalized  VARCHAR(20),
    ADD COLUMN preferred_language    VARCHAR(50),
    ADD COLUMN best_time_to_call     VARCHAR(50),
    ADD COLUMN consent_call          BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN consent_sms           BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN consent_email         BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN do_not_contact        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN street                VARCHAR(255),
    ADD COLUMN city                  VARCHAR(100),
    ADD COLUMN state                 VARCHAR(50),
    ADD COLUMN zip                   VARCHAR(20),
    ADD COLUMN campaign              VARCHAR(150),
    ADD COLUMN referral_partner_id   UUID REFERENCES crm_referral_partners(id) ON DELETE SET NULL,
    ADD COLUMN preferred_location    VARCHAR(150),
    ADD COLUMN reason_for_visit      TEXT,
    ADD COLUMN insurance_plan        VARCHAR(150),
    ADD COLUMN insurance_member_id   VARCHAR(100),
    ADD COLUMN insurance_verified    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN owner_user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN lost_reason           VARCHAR(255),
    ADD COLUMN mrn                   VARCHAR(100),
    ADD COLUMN ehr_patient_id        VARCHAR(100),
    ADD COLUMN ehr_sync_status       VARCHAR(30) NOT NULL DEFAULT 'NOT_LINKED',
    ADD COLUMN ehr_synced_at         TIMESTAMPTZ,
    ADD COLUMN converted_at          TIMESTAMPTZ,
    ADD COLUMN tags                  VARCHAR(500),
    ADD COLUMN created_by_user_id    UUID REFERENCES users(id) ON DELETE SET NULL;

UPDATE crm_patients SET
    first_name = split_part(full_name, ' ', 1),
    last_name = NULLIF(trim(substr(full_name, length(split_part(full_name, ' ', 1)) + 1)), ''),
    phone_normalized = NULLIF(right(regexp_replace(coalesce(phone, ''), '[^0-9]', '', 'g'), 10), ''),
    converted_at = CASE WHEN lifecycle_stage = 'PATIENT' THEN updated_at END;

CREATE INDEX idx_crm_patients_phone ON crm_patients(organization_id, phone_normalized);
CREATE INDEX idx_crm_patients_alt_phone ON crm_patients(organization_id, alt_phone_normalized);
CREATE INDEX idx_crm_patients_email ON crm_patients(organization_id, lower(email));
CREATE INDEX idx_crm_patients_status ON crm_patients(organization_id, lifecycle_stage, lead_status);
CREATE INDEX idx_crm_patients_owner ON crm_patients(organization_id, owner_user_id);

-- ---------------------------------------------------------------------
-- Referrals
-- ---------------------------------------------------------------------
CREATE TABLE crm_referrals (
    id                        UUID PRIMARY KEY,
    organization_id           UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    patient_id                UUID NOT NULL REFERENCES crm_patients(id) ON DELETE CASCADE,
    referral_partner_id       UUID REFERENCES crm_referral_partners(id) ON DELETE SET NULL,
    direction                 VARCHAR(20) NOT NULL DEFAULT 'INBOUND',
    status                    crm_referral_status NOT NULL DEFAULT 'RECEIVED',
    priority                  crm_priority NOT NULL DEFAULT 'MEDIUM',
    referring_provider_name   VARCHAR(150),
    referring_provider_npi    VARCHAR(20),
    referred_to_provider      VARCHAR(150),
    service_requested         VARCHAR(150),
    reason                    TEXT,
    diagnosis_codes           VARCHAR(255),
    insurance_payer           VARCHAR(150),
    authorization_number      VARCHAR(100),
    authorization_expires_on  DATE,
    received_on               DATE NOT NULL DEFAULT CURRENT_DATE,
    due_on                    DATE,
    assigned_user_id          UUID REFERENCES users(id) ON DELETE SET NULL,
    assigned_name             VARCHAR(150),
    closed_reason             VARCHAR(255),
    closed_at                 TIMESTAMPTZ,
    notes                     TEXT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_crm_referrals_org ON crm_referrals(organization_id, status);
CREATE INDEX idx_crm_referrals_patient ON crm_referrals(patient_id);
CREATE INDEX idx_crm_referrals_partner ON crm_referrals(referral_partner_id);

-- ---------------------------------------------------------------------
-- Opportunities (service-line / treatment pipeline)
-- ---------------------------------------------------------------------
CREATE TABLE crm_opportunities (
    id                   UUID PRIMARY KEY,
    organization_id      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    patient_id           UUID NOT NULL REFERENCES crm_patients(id) ON DELETE CASCADE,
    referral_id          UUID REFERENCES crm_referrals(id) ON DELETE SET NULL,
    name                 VARCHAR(255) NOT NULL,
    service_line         VARCHAR(150),
    stage                crm_opportunity_stage NOT NULL DEFAULT 'INQUIRY',
    priority             crm_priority NOT NULL DEFAULT 'MEDIUM',
    estimated_value      NUMERIC(12, 2),
    probability          INTEGER,
    expected_close_date  DATE,
    provider_name        VARCHAR(150),
    owner_user_id        UUID REFERENCES users(id) ON DELETE SET NULL,
    owner_name           VARCHAR(150),
    lost_reason          VARCHAR(255),
    closed_at            TIMESTAMPTZ,
    notes                TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_crm_opps_org ON crm_opportunities(organization_id, stage);
CREATE INDEX idx_crm_opps_patient ON crm_opportunities(patient_id);

-- ---------------------------------------------------------------------
-- Appointments & meetings scheduled from the CRM
-- ---------------------------------------------------------------------
CREATE TABLE crm_appointments (
    id                  UUID PRIMARY KEY,
    organization_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    patient_id          UUID NOT NULL REFERENCES crm_patients(id) ON DELETE CASCADE,
    opportunity_id      UUID REFERENCES crm_opportunities(id) ON DELETE SET NULL,
    referral_id         UUID REFERENCES crm_referrals(id) ON DELETE SET NULL,
    title               VARCHAR(255) NOT NULL,
    appointment_type    VARCHAR(50) NOT NULL DEFAULT 'NEW_PATIENT',
    mode                VARCHAR(30) NOT NULL DEFAULT 'IN_PERSON',
    status              crm_appointment_status NOT NULL DEFAULT 'SCHEDULED',
    starts_at           TIMESTAMPTZ NOT NULL,
    duration_minutes    INTEGER NOT NULL DEFAULT 30,
    provider_name       VARCHAR(150),
    location            VARCHAR(150),
    assigned_user_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    assigned_name       VARCHAR(150),
    ehr_appointment_id  VARCHAR(100),
    ehr_sync_status     VARCHAR(30) NOT NULL DEFAULT 'NOT_LINKED',
    cancel_reason       VARCHAR(255),
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_crm_appts_org_time ON crm_appointments(organization_id, starts_at);
CREATE INDEX idx_crm_appts_patient ON crm_appointments(patient_id);

-- ---------------------------------------------------------------------
-- Tasks & follow-ups
-- ---------------------------------------------------------------------
CREATE TABLE crm_tasks (
    id                 UUID PRIMARY KEY,
    organization_id    UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    patient_id         UUID REFERENCES crm_patients(id) ON DELETE CASCADE,
    opportunity_id     UUID REFERENCES crm_opportunities(id) ON DELETE SET NULL,
    referral_id        UUID REFERENCES crm_referrals(id) ON DELETE SET NULL,
    title              VARCHAR(255) NOT NULL,
    task_type          VARCHAR(50) NOT NULL DEFAULT 'CALL',
    priority           crm_priority NOT NULL DEFAULT 'MEDIUM',
    status             crm_task_status NOT NULL DEFAULT 'OPEN',
    due_at             TIMESTAMPTZ NOT NULL,
    assigned_user_id   UUID REFERENCES users(id) ON DELETE SET NULL,
    assigned_name      VARCHAR(150),
    completed_at       TIMESTAMPTZ,
    completed_by_name  VARCHAR(150),
    notes              TEXT,
    created_by_name    VARCHAR(150),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_crm_tasks_org ON crm_tasks(organization_id, status, due_at);
CREATE INDEX idx_crm_tasks_patient ON crm_tasks(patient_id);

-- ---------------------------------------------------------------------
-- Activity timeline (calls, notes, sms, email, status changes)
-- ---------------------------------------------------------------------
CREATE TABLE crm_activities (
    id                UUID PRIMARY KEY,
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    patient_id        UUID REFERENCES crm_patients(id) ON DELETE CASCADE,
    opportunity_id    UUID REFERENCES crm_opportunities(id) ON DELETE SET NULL,
    referral_id       UUID REFERENCES crm_referrals(id) ON DELETE SET NULL,
    activity_type     crm_activity_type NOT NULL,
    subject           VARCHAR(255) NOT NULL,
    body              TEXT,
    phone             VARCHAR(50),
    call_outcome      VARCHAR(100),
    duration_seconds  INTEGER,
    actor_user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    actor_name        VARCHAR(150),
    occurred_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_crm_activities_org ON crm_activities(organization_id, occurred_at DESC);
CREATE INDEX idx_crm_activities_patient ON crm_activities(patient_id, occurred_at DESC);
CREATE INDEX idx_crm_activities_opp ON crm_activities(opportunity_id);

-- ---------------------------------------------------------------------
-- Targets & quotas
-- ---------------------------------------------------------------------
CREATE TABLE crm_targets (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id          UUID REFERENCES users(id) ON DELETE CASCADE,
    assignee_name    VARCHAR(150),
    metric           VARCHAR(40) NOT NULL,
    target_value     NUMERIC(14, 2) NOT NULL,
    period_label     VARCHAR(60),
    period_start     DATE NOT NULL,
    period_end       DATE NOT NULL,
    notes            TEXT,
    created_by_name  VARCHAR(150),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_crm_target_period CHECK (period_end >= period_start)
);

CREATE INDEX idx_crm_targets_org ON crm_targets(organization_id, period_start);

-- ---------------------------------------------------------------------
-- Integrations (phone system, EHR) — configuration only; credentials are
-- never stored here.
-- ---------------------------------------------------------------------
CREATE TABLE crm_integrations (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    kind             VARCHAR(20) NOT NULL,
    provider         VARCHAR(100),
    status           VARCHAR(30) NOT NULL DEFAULT 'NOT_CONNECTED',
    settings_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_sync_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_crm_integration_kind UNIQUE (organization_id, kind)
);
