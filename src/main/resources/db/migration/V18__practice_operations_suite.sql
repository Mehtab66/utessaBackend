-- Practice Operations suite: insurance credentialing, hospital privileges, provider/staff trackers,
-- document vault, resources, training, vendor files/invoices, specialty templates and inventory.
-- Time Clock is a separate module and is not part of this migration.

-- Which defaults have been created for an organization (so deleting them never re-seeds).
CREATE TABLE ops_seed_state (
    organization_id  UUID PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    payers           BOOLEAN NOT NULL DEFAULT FALSE,
    trackers         BOOLEAN NOT NULL DEFAULT FALSE,
    specialties      BOOLEAN NOT NULL DEFAULT FALSE,
    resources        BOOLEAN NOT NULL DEFAULT FALSE,
    training         BOOLEAN NOT NULL DEFAULT FALSE
);

-- ── Insurance credentialing ────────────────────────────────────────────────
CREATE TABLE ops_payers (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name             VARCHAR(200) NOT NULL,
    short_name       VARCHAR(60)  NOT NULL,
    payer_type       VARCHAR(60),
    method           VARCHAR(200),
    typical_days     INTEGER,
    recred_months    INTEGER,
    active           BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order       INTEGER NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ops_payers_org ON ops_payers(organization_id);

CREATE TABLE ops_payer_enrollments (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    staff_id         UUID NOT NULL REFERENCES staff_members(id) ON DELETE CASCADE,
    payer_id         UUID NOT NULL REFERENCES ops_payers(id) ON DELETE CASCADE,
    status           VARCHAR(60) NOT NULL,
    products         VARCHAR(200),
    enrollment_type  VARCHAR(150),
    group_info       VARCHAR(255),
    method           VARCHAR(200),
    submitted_on     DATE,
    reference        VARCHAR(100),
    provider_pin     VARCHAR(100),
    effective_on     DATE,
    recred_due       DATE,
    follow_up_on     DATE,
    owner_name       VARCHAR(150),
    notes            TEXT,
    history          JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ops_enrollment UNIQUE (staff_id, payer_id)
);
CREATE INDEX idx_ops_enroll_org ON ops_payer_enrollments(organization_id);

-- ── Hospital privileges ─────────────────────────────────────────────────────
CREATE TABLE ops_facilities (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name             VARCHAR(200) NOT NULL,
    facility_type    VARCHAR(80),
    contact          VARCHAR(255),
    cycle_months     INTEGER,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ops_facilities_org ON ops_facilities(organization_id);

CREATE TABLE ops_privileges (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    staff_id         UUID NOT NULL REFERENCES staff_members(id) ON DELETE CASCADE,
    facility_id      UUID NOT NULL REFERENCES ops_facilities(id) ON DELETE CASCADE,
    category         VARCHAR(120),
    department       VARCHAR(150),
    privileges       TEXT,
    status           VARCHAR(60) NOT NULL,
    applied_on       DATE,
    approved_on      DATE,
    reappoint_due    DATE,
    fppe_status      VARCHAR(40),
    fppe_due         DATE,
    oppe_due         DATE,
    contact          VARCHAR(255),
    notes            TEXT,
    history          JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ops_priv_org ON ops_privileges(organization_id);

-- ── Configurable trackers (CME, education, malpractice, peer review, contracts, onboarding…) ──
CREATE TABLE ops_tracker_tabs (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    code             VARCHAR(40),
    scope            VARCHAR(20) NOT NULL,          -- PROVIDER | STAFF
    name             VARCHAR(120) NOT NULL,
    group_name       VARCHAR(80),
    description      VARCHAR(500),
    fields           JSONB NOT NULL DEFAULT '[]'::jsonb,
    goal             JSONB,
    standard         BOOLEAN NOT NULL DEFAULT FALSE,
    hidden           BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order       INTEGER NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ops_tabs_org ON ops_tracker_tabs(organization_id, scope);

CREATE TABLE ops_tracker_rows (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    tab_id           UUID NOT NULL REFERENCES ops_tracker_tabs(id) ON DELETE CASCADE,
    staff_id         UUID NOT NULL REFERENCES staff_members(id) ON DELETE CASCADE,
    vals             JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ops_rows_tab ON ops_tracker_rows(organization_id, tab_id);
CREATE INDEX idx_ops_rows_staff ON ops_tracker_rows(organization_id, staff_id);

-- ── Vendors: specialty, files, invoices, specialty templates ────────────────
ALTER TABLE vendors ADD COLUMN IF NOT EXISTS specialty VARCHAR(100);

CREATE TABLE ops_specialty_templates (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name             VARCHAR(120) NOT NULL,
    folders          JSONB NOT NULL DEFAULT '[]'::jsonb,
    credentials      JSONB NOT NULL DEFAULT '[]'::jsonb,
    intake           JSONB NOT NULL DEFAULT '[]'::jsonb,
    system_template  BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order       INTEGER NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ops_spec_org ON ops_specialty_templates(organization_id);

-- ── Folders and files (vendor files, resources, training, document vault) ──
CREATE TABLE ops_folders (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    space            VARCHAR(20) NOT NULL,          -- VENDOR | RESOURCES | TRAINING
    vendor_id        UUID REFERENCES vendors(id) ON DELETE CASCADE,
    parent_id        UUID REFERENCES ops_folders(id) ON DELETE CASCADE,
    name             VARCHAR(150) NOT NULL,
    locked           BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order       INTEGER NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ops_folders_space ON ops_folders(organization_id, space);

CREATE TABLE ops_files (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    space            VARCHAR(20) NOT NULL,          -- VAULT | VENDOR | RESOURCES | TRAINING
    folder_id        UUID REFERENCES ops_folders(id) ON DELETE CASCADE,
    vendor_id        UUID REFERENCES vendors(id) ON DELETE CASCADE,
    staff_id         UUID REFERENCES staff_members(id) ON DELETE CASCADE,
    category         VARCHAR(120),
    doc_type         VARCHAR(200),
    name             VARCHAR(255) NOT NULL,
    content_type     VARCHAR(150),
    size_bytes       BIGINT,
    body_text        TEXT,
    expires_on       DATE,
    notes            TEXT,
    link_ref         VARCHAR(120),
    uploaded_by      VARCHAR(150),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ops_files_space ON ops_files(organization_id, space);
CREATE INDEX idx_ops_files_staff ON ops_files(organization_id, staff_id);
CREATE INDEX idx_ops_files_vendor ON ops_files(organization_id, vendor_id);

CREATE TABLE ops_file_blobs (
    file_id          UUID PRIMARY KEY REFERENCES ops_files(id) ON DELETE CASCADE,
    data             BYTEA NOT NULL
);

CREATE TABLE ops_vendor_invoices (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    vendor_id        UUID NOT NULL REFERENCES vendors(id) ON DELETE CASCADE,
    number           VARCHAR(100) NOT NULL,
    invoice_date     DATE,
    due_date         DATE,
    amount           NUMERIC(12, 2),
    description      VARCHAR(500),
    status           VARCHAR(30) NOT NULL DEFAULT 'Unpaid',
    paid_on          DATE,
    paid_amount      NUMERIC(12, 2),
    method           VARCHAR(60),
    reference        VARCHAR(100),
    file_id          UUID REFERENCES ops_files(id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ops_invoices_org ON ops_vendor_invoices(organization_id, vendor_id);

-- ── Inventory ───────────────────────────────────────────────────────────────
CREATE TABLE ops_inventory_items (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name             VARCHAR(255) NOT NULL,
    sku              VARCHAR(100),
    manufacturer     VARCHAR(150),
    vendor_name      VARCHAR(150),
    category         VARCHAR(100),
    unit             VARCHAR(40),
    price            NUMERIC(12, 2),
    qty              INTEGER NOT NULL DEFAULT 0,
    reorder_level    INTEGER,
    par_level        INTEGER,
    location         VARCHAR(150),
    lot              VARCHAR(80),
    expires_on       DATE,
    notes            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ops_items_org ON ops_inventory_items(organization_id);

CREATE TABLE ops_assets (
    id                 UUID PRIMARY KEY,
    organization_id    UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    tag                VARCHAR(60),
    asset_type         VARCHAR(100) NOT NULL,
    name               VARCHAR(255) NOT NULL,
    manufacturer       VARCHAR(150),
    serial             VARCHAR(120),
    location           VARCHAR(150),
    room               VARCHAR(120),
    assigned_staff_id  UUID REFERENCES staff_members(id) ON DELETE SET NULL,
    status             VARCHAR(40) NOT NULL DEFAULT 'In use',
    purchased_on       DATE,
    price              NUMERIC(12, 2),
    warranty_ends      DATE,
    pm_months          INTEGER,
    last_pm_on         DATE,
    vendor_name        VARCHAR(150),
    notes              TEXT,
    it_host            VARCHAR(150),
    it_ip              VARCHAR(80),
    it_username        VARCHAR(150),
    it_password_enc    TEXT,
    it_os              VARCHAR(120),
    it_rotated_on      DATE,
    service            JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ops_assets_org ON ops_assets(organization_id);

CREATE TABLE ops_inventory_requests (
    id                 UUID PRIMARY KEY,
    organization_id    UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    item_id            UUID REFERENCES ops_inventory_items(id) ON DELETE SET NULL,
    name               VARCHAR(255) NOT NULL,
    sku                VARCHAR(100),
    qty                INTEGER NOT NULL,
    unit               VARCHAR(40),
    requested_by       UUID,
    requested_by_name  VARCHAR(150),
    needed_by          DATE,
    priority           VARCHAR(20) NOT NULL DEFAULT 'Routine',
    reason             TEXT,
    status             VARCHAR(30) NOT NULL DEFAULT 'Requested',
    estimate           NUMERIC(12, 2),
    po_number          VARCHAR(80),
    vendor_name        VARCHAR(150),
    eta                DATE,
    unit_cost          NUMERIC(12, 2),
    log                JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ops_requests_org ON ops_inventory_requests(organization_id, status);
