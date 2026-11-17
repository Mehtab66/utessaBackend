-- =====================================================================
-- V8 · Incoming-call flow, coverage details, recall (no-show) call list
-- =====================================================================

-- Coverage captured during new-patient intake
ALTER TABLE crm_patients
    ADD COLUMN coverage_type      VARCHAR(20) NOT NULL DEFAULT 'INSURANCE',
    ADD COLUMN insurance_group    VARCHAR(100),
    ADD COLUMN policy_holder      VARCHAR(30),
    ADD COLUMN sms_reminders      BOOLEAN NOT NULL DEFAULT FALSE;

-- Call documentation: reason, outcome, EHR filing, and calls from non-patients
ALTER TABLE crm_activities
    ADD COLUMN call_reason      VARCHAR(100),
    ADD COLUMN caller_name      VARCHAR(150),
    ADD COLUMN caller_type      VARCHAR(40),
    ADD COLUMN phone_line       VARCHAR(40),
    ADD COLUMN ehr_sync_status  VARCHAR(30) NOT NULL DEFAULT 'NOT_LINKED';

CREATE INDEX idx_crm_activities_calls ON crm_activities(organization_id, activity_type, occurred_at DESC);

-- ---------------------------------------------------------------------
-- Recall call list: missed and cancelled visits to call back and rebook
-- ---------------------------------------------------------------------
CREATE TYPE crm_recall_status AS ENUM ('TO_CALL', 'REBOOKED', 'NOT_REACHED', 'CLOSED');

CREATE TABLE crm_recalls (
    id                        UUID PRIMARY KEY,
    organization_id           UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    patient_id                UUID NOT NULL REFERENCES crm_patients(id) ON DELETE CASCADE,
    appointment_id            UUID REFERENCES crm_appointments(id) ON DELETE SET NULL,
    missed_at                 TIMESTAMPTZ NOT NULL,
    miss_type                 VARCHAR(20) NOT NULL,
    visit_type                VARCHAR(100),
    provider_name             VARCHAR(150),
    source                    VARCHAR(20) NOT NULL DEFAULT 'UTESSA',
    status                    crm_recall_status NOT NULL DEFAULT 'TO_CALL',
    attempts                  INTEGER NOT NULL DEFAULT 0,
    last_outcome              VARCHAR(100),
    last_attempt_at           TIMESTAMPTZ,
    next_attempt_on           DATE,
    rebooked_appointment_id   UUID REFERENCES crm_appointments(id) ON DELETE SET NULL,
    close_reason              VARCHAR(255),
    notes                     TEXT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_crm_recall_visit UNIQUE (organization_id, patient_id, missed_at)
);

CREATE INDEX idx_crm_recalls_org ON crm_recalls(organization_id, status);
CREATE INDEX idx_crm_recalls_patient ON crm_recalls(patient_id);
