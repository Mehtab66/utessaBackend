ALTER TABLE staff_members
    ADD COLUMN IF NOT EXISTS tax_id VARCHAR(50),
    ADD COLUMN IF NOT EXISTS malpractice_carrier VARCHAR(150),
    ADD COLUMN IF NOT EXISTS malpractice_policy VARCHAR(100),
    ADD COLUMN IF NOT EXISTS malpractice_expires_on DATE,
    ADD COLUMN IF NOT EXISTS onboarding_steps_json JSONB NOT NULL DEFAULT '[]'::jsonb;
