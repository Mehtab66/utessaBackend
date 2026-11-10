CREATE TYPE employment_status AS ENUM (
    'ONBOARDING',
    'ACTIVE',
    'SUSPENDED',
    'INACTIVE'
);

CREATE TYPE vendor_baa_status AS ENUM (
    'SIGNED',
    'PENDING',
    'MISSING',
    'NOT_REQUIRED'
);

CREATE TYPE lead_status AS ENUM (
    'NEW',
    'CONTACTED',
    'SCHEDULED',
    'SHOWED',
    'CONVERTED',
    'CLOSED'
);

CREATE TABLE staff_members (
    id                  UUID PRIMARY KEY,
    organization_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    first_name          VARCHAR(100) NOT NULL,
    last_name           VARCHAR(100) NOT NULL,
    email               VARCHAR(255),
    phone               VARCHAR(50),
    job_title           VARCHAR(150) NOT NULL,
    department          VARCHAR(150),
    category            VARCHAR(80) NOT NULL,
    is_provider         BOOLEAN NOT NULL DEFAULT FALSE,
    status              employment_status NOT NULL DEFAULT 'ONBOARDING',
    npi                 VARCHAR(20),
    license_type        VARCHAR(100),
    license_number      VARCHAR(100),
    license_expires_on  DATE,
    location            VARCHAR(150),
    start_date          DATE,
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_staff_org ON staff_members(organization_id);
CREATE INDEX idx_staff_org_provider ON staff_members(organization_id, is_provider);
CREATE INDEX idx_staff_org_status ON staff_members(organization_id, status);

CREATE TABLE vendors (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name             VARCHAR(255) NOT NULL,
    category         VARCHAR(100) NOT NULL,
    contact_name     VARCHAR(150),
    email            VARCHAR(255),
    phone            VARCHAR(50),
    baa_status       vendor_baa_status NOT NULL DEFAULT 'PENDING',
    baa_signed_on    DATE,
    website          VARCHAR(255),
    notes            TEXT,
    active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_vendors_org ON vendors(organization_id);
CREATE INDEX idx_vendors_baa ON vendors(organization_id, baa_status);

CREATE TABLE patient_leads (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    full_name        VARCHAR(255) NOT NULL,
    phone            VARCHAR(50),
    email            VARCHAR(255),
    source           VARCHAR(100),
    status           lead_status NOT NULL DEFAULT 'NEW',
    owner_name       VARCHAR(150),
    notes            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_leads_org ON patient_leads(organization_id);
CREATE INDEX idx_leads_status ON patient_leads(organization_id, status);
