CREATE TABLE users (
    id              UUID PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    full_name       VARCHAR(255) NOT NULL,
    phone           VARCHAR(50),
    email_verified  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE organizations (
    id               UUID PRIMARY KEY,
    name             VARCHAR(255) NOT NULL,
    slug             VARCHAR(255) NOT NULL UNIQUE,
    team_size_band   INTEGER,
    org_type         VARCHAR(100),
    ehr_name         VARCHAR(255),
    trial_starts_at  TIMESTAMPTZ NOT NULL,
    trial_ends_at    TIMESTAMPTZ NOT NULL,
    terms_accepted   BOOLEAN NOT NULL DEFAULT FALSE,
    news_opt_in      BOOLEAN NOT NULL DEFAULT FALSE,
    onboarding_done  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE organization_services (
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service         VARCHAR(50) NOT NULL,
    PRIMARY KEY (organization_id, service)
);

CREATE TABLE memberships (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    role             VARCHAR(30) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_membership_user_org UNIQUE (user_id, organization_id)
);

CREATE INDEX idx_memberships_org ON memberships(organization_id);
CREATE INDEX idx_memberships_user ON memberships(user_id);

CREATE TABLE email_verifications (
    id          UUID PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,
    code_hash   VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_email_verifications_email ON email_verifications(email);
