-- The normalized request of each billing operation (module keys, seat count, interval — no card
-- or personal data). The recovery job uses it to check in Stripe whether an operation with an
-- unknown outcome (e.g. a timeout) actually took effect.
ALTER TABLE stripe_idempotency_records ADD COLUMN request_body TEXT;
