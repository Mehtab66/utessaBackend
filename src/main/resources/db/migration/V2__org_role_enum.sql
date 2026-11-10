-- Harden membership role: PostgreSQL ENUM (rejects unknown values at the DB)
CREATE TYPE org_role AS ENUM (
    'OWNER',
    'ADMIN',
    'MANAGER',
    'FRONT',
    'CLINICAL',
    'PROVIDER',
    'BILLING',
    'OUTREACH',
    'HR',
    'MEMBER'
);

ALTER TABLE memberships
    ALTER COLUMN role TYPE org_role
    USING role::org_role;
