#!/usr/bin/env bash
# Create (or upsert) a PLATFORM SUPERADMIN.
# This is NOT an organization membership — no org / memberships row is created.
#
# Usage:
#   ./scripts/create-superadmin.sh
#   EMAIL=you@example.com PASSWORD='your-strong-password' ./scripts/create-superadmin.sh
#
# DB defaults match local .env (override with DB_*).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

EMAIL="${EMAIL:-superadmin@utessa.local}"
PASSWORD="${PASSWORD:-ChangeMe!SuperAdmin}"
FULL_NAME="${FULL_NAME:-Utessa Super Admin}"

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-utessa_crm}"
DB_USERNAME="${DB_USERNAME:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-root123}"

if [[ ! "$EMAIL" =~ ^[^@]+@[^@]+\.[^@]+$ ]]; then
  echo "Invalid EMAIL: $EMAIL" >&2
  exit 1
fi

if [[ ${#PASSWORD} -lt 12 ]]; then
  echo "PASSWORD must be at least 12 characters." >&2
  exit 1
fi

echo "Generating BCrypt password hash…"
HASH="$(
  python3 - <<PY
import sys
try:
    import bcrypt
except ImportError:
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "--user", "bcrypt", "-q"])
    import bcrypt
pw = """${PASSWORD}""".encode("utf-8")
print(bcrypt.hashpw(pw, bcrypt.gensalt(rounds=10)).decode("ascii"))
PY
)"

export PGPASSWORD="$DB_PASSWORD"

echo "Upserting PLATFORM SUPERADMIN → $EMAIL (no organization membership)"
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d "$DB_NAME" \
  -v ON_ERROR_STOP=1 \
  -v email="$EMAIL" \
  -v full_name="$FULL_NAME" \
  -v hash="$HASH" \
  <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO users (
  id, email, password_hash, full_name, phone, email_verified, platform_role, created_at, updated_at
)
VALUES (
  gen_random_uuid(),
  lower(:'email'),
  :'hash',
  :'full_name',
  NULL,
  TRUE,
  'SUPERADMIN'::platform_role,
  NOW(),
  NOW()
)
ON CONFLICT (email) DO UPDATE
  SET password_hash = EXCLUDED.password_hash,
      full_name = EXCLUDED.full_name,
      email_verified = TRUE,
      platform_role = 'SUPERADMIN'::platform_role,
      updated_at = NOW();

-- Ensure this operator is not bound to any practice membership
DELETE FROM memberships m
USING users u
WHERE m.user_id = u.id
  AND lower(u.email) = lower(:'email');

SELECT
  email,
  platform_role::text AS platform_role,
  email_verified,
  (SELECT COUNT(*) FROM memberships m WHERE m.user_id = users.id) AS membership_count
FROM users
WHERE lower(email) = lower(:'email');
SQL

echo
echo "Done. This account monitors the platform — it has no practice org role."
echo "  email:    $EMAIL"
echo "  password: (the PASSWORD you supplied)"
echo
echo "Change the default password immediately after first login."
