-- The open Stripe Checkout session of an organization, if any. Starting a new checkout expires the
-- previous one first, so two checkouts can never both complete and create two subscriptions.
ALTER TABLE organization_subscriptions
    ADD COLUMN pending_checkout_session_id  VARCHAR(255),
    ADD COLUMN pending_checkout_expires_at  TIMESTAMPTZ;
