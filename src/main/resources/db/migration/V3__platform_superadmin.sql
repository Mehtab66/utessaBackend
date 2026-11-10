-- Platform operators are NOT organization members.
-- SUPERADMIN lives on users.platform_role — never on memberships.role / org_role.
CREATE TYPE platform_role AS ENUM (
    'NONE',
    'SUPERADMIN'
);

ALTER TABLE users
    ADD COLUMN platform_role platform_role NOT NULL DEFAULT 'NONE';

CREATE INDEX idx_users_platform_role ON users(platform_role)
    WHERE platform_role <> 'NONE';
