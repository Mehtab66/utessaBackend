-- When the practice first started paid billing. Once set, the free trial is over for good:
-- cancelling later does not give the remaining trial days back.
ALTER TABLE organization_subscriptions ADD COLUMN paid_billing_started_at TIMESTAMPTZ;

-- Backfill: subscriptions that are (or were) billing, and practices with a paid invoice.
UPDATE organization_subscriptions s
SET paid_billing_started_at = COALESCE(
        (SELECT MIN(COALESCE(i.paid_at, i.stripe_created_at, i.created_at))
         FROM subscription_invoices i
         WHERE i.organization_id = s.organization_id AND i.amount_paid > 0),
        s.current_period_start,
        s.updated_at)
WHERE s.paid_billing_started_at IS NULL
  AND (s.subscription_status IN ('active', 'past_due', 'unpaid')
       OR EXISTS (SELECT 1 FROM subscription_invoices i
                  WHERE i.organization_id = s.organization_id AND i.amount_paid > 0));
