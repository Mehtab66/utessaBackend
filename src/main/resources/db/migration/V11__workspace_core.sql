-- =====================================================================
-- V11 · Workspace core
--  Always-on features that every practice gets regardless of which
--  modules (CRM / Operations / Marketing / Time clock) are enabled:
--  personal + team work items, a shared calendar, notes and a team
--  announcement board.
-- =====================================================================

CREATE TABLE workspace_tasks (
    id                  UUID PRIMARY KEY,
    organization_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    title               VARCHAR(255) NOT NULL,
    notes               TEXT,
    status              VARCHAR(20) NOT NULL DEFAULT 'TODO',
    priority            VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    due_date            DATE,
    assignee_user_id    UUID,
    assignee_name       VARCHAR(150),
    created_by_user_id  UUID,
    created_by_name     VARCHAR(150),
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ws_tasks_org ON workspace_tasks(organization_id, status);
CREATE INDEX idx_ws_tasks_assignee ON workspace_tasks(organization_id, assignee_user_id);

CREATE TABLE workspace_events (
    id                  UUID PRIMARY KEY,
    organization_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    title               VARCHAR(255) NOT NULL,
    description         TEXT,
    location            VARCHAR(255),
    category            VARCHAR(30) NOT NULL DEFAULT 'MEETING',
    starts_at           TIMESTAMPTZ NOT NULL,
    ends_at             TIMESTAMPTZ NOT NULL,
    all_day             BOOLEAN NOT NULL DEFAULT FALSE,
    created_by_user_id  UUID,
    created_by_name     VARCHAR(150),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ws_events_org_start ON workspace_events(organization_id, starts_at);

CREATE TABLE workspace_notes (
    id                  UUID PRIMARY KEY,
    organization_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    title               VARCHAR(255) NOT NULL,
    body                TEXT,
    color               VARCHAR(20) NOT NULL DEFAULT 'yellow',
    pinned              BOOLEAN NOT NULL DEFAULT FALSE,
    shared              BOOLEAN NOT NULL DEFAULT FALSE,
    owner_user_id       UUID NOT NULL,
    owner_name          VARCHAR(150),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ws_notes_org_owner ON workspace_notes(organization_id, owner_user_id);

CREATE TABLE workspace_announcements (
    id                  UUID PRIMARY KEY,
    organization_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    title               VARCHAR(255) NOT NULL,
    body                TEXT NOT NULL,
    category            VARCHAR(30) NOT NULL DEFAULT 'GENERAL',
    pinned              BOOLEAN NOT NULL DEFAULT FALSE,
    author_user_id      UUID,
    author_name         VARCHAR(150),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ws_announcements_org ON workspace_announcements(organization_id, created_at DESC);
