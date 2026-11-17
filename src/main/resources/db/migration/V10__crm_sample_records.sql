-- V10 · Tracks rows created by the "Load sample data" tool so they can be removed exactly.
CREATE TABLE crm_sample_records (
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    entity_type      VARCHAR(40) NOT NULL,
    entity_id        UUID NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (entity_type, entity_id)
);

CREATE INDEX idx_crm_sample_records_org ON crm_sample_records(organization_id, entity_type);
