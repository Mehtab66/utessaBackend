-- Time Clock module: punches (with breaks), timesheet approval per pay period, time off (PTO),
-- payroll settings and an audit trail of every change to a time entry.

-- One row per practice: pay period, overtime rules and rounding.
CREATE TABLE tc_settings (
    organization_id       UUID PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    timezone              VARCHAR(64)  NOT NULL DEFAULT 'America/New_York',
    pay_period            VARCHAR(20)  NOT NULL DEFAULT 'BIWEEKLY',  -- WEEKLY | BIWEEKLY | SEMI_MONTHLY
    period_anchor         DATE         NOT NULL,                     -- first day of any weekly/biweekly period
    week_start            VARCHAR(10)  NOT NULL DEFAULT 'MONDAY',    -- start of the overtime workweek
    daily_ot_hours        NUMERIC(5,2),                              -- e.g. 8 (California); null = no daily overtime
    weekly_ot_hours       NUMERIC(5,2) DEFAULT 40,                   -- null = no weekly overtime
    rounding_minutes      INTEGER      NOT NULL DEFAULT 0,           -- 0 = exact; 5, 6, 10 or 15
    allow_manual_entries  BOOLEAN      NOT NULL DEFAULT TRUE,        -- staff may add a missed punch themselves
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- A shift: clock in → clock out. Breaks are tracked on the shift itself.
CREATE TABLE tc_entries (
    id                UUID PRIMARY KEY,
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    clock_in          TIMESTAMPTZ NOT NULL,
    clock_out         TIMESTAMPTZ,
    break_minutes     INTEGER NOT NULL DEFAULT 0,      -- finished breaks
    break_started_at  TIMESTAMPTZ,                     -- set while on a break
    note              TEXT,
    source            VARCHAR(20) NOT NULL DEFAULT 'WEB',  -- WEB (punched) | MANUAL (added afterwards)
    edited            BOOLEAN NOT NULL DEFAULT FALSE,
    created_by        UUID,
    updated_by        UUID,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT tc_entries_out_after_in CHECK (clock_out IS NULL OR clock_out > clock_in),
    CONSTRAINT tc_entries_break_nonneg CHECK (break_minutes >= 0)
);
CREATE INDEX idx_tc_entries_org_user_in ON tc_entries(organization_id, user_id, clock_in);
CREATE INDEX idx_tc_entries_org_in ON tc_entries(organization_id, clock_in);
-- Nobody can be clocked in twice at once.
CREATE UNIQUE INDEX uq_tc_entries_one_open ON tc_entries(organization_id, user_id) WHERE clock_out IS NULL;

-- Who changed which time entry, when and why (kept even after an entry is deleted).
CREATE TABLE tc_entry_audit (
    id                UUID PRIMARY KEY,
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    entry_id          UUID NOT NULL,
    subject_user_id   UUID NOT NULL,
    actor_user_id     UUID,
    actor_name        VARCHAR(255),
    action            VARCHAR(20) NOT NULL,   -- CREATED | UPDATED | DELETED
    summary           TEXT NOT NULL,
    reason            TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_tc_entry_audit_entry ON tc_entry_audit(organization_id, entry_id);

-- Approval state of one person's pay period (no row = still open).
CREATE TABLE tc_timesheets (
    id                UUID PRIMARY KEY,
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    period_start      DATE NOT NULL,
    period_end        DATE NOT NULL,
    status            VARCHAR(20) NOT NULL,   -- SUBMITTED | APPROVED | REJECTED
    submitted_at      TIMESTAMPTZ,
    decided_by        UUID,
    decided_by_name   VARCHAR(255),
    decided_at        TIMESTAMPTZ,
    note              TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tc_timesheets_period UNIQUE (organization_id, user_id, period_start)
);

-- Time off requests.
CREATE TABLE tc_pto_requests (
    id                UUID PRIMARY KEY,
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pto_type          VARCHAR(20) NOT NULL,   -- VACATION | SICK | PERSONAL | BEREAVEMENT | UNPAID
    start_date        DATE NOT NULL,
    end_date          DATE NOT NULL,
    hours             NUMERIC(6,2) NOT NULL,
    reason            TEXT,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | APPROVED | DENIED | CANCELLED
    decided_by        UUID,
    decided_by_name   VARCHAR(255),
    decided_at        TIMESTAMPTZ,
    decision_note     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT tc_pto_dates CHECK (end_date >= start_date),
    CONSTRAINT tc_pto_hours CHECK (hours > 0)
);
CREATE INDEX idx_tc_pto_org_user ON tc_pto_requests(organization_id, user_id, start_date);
