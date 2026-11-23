-- Undoing a change that was booked for the next renewal (releases the Stripe subscription schedule).
ALTER TYPE billing_operation_type ADD VALUE IF NOT EXISTS 'CANCEL_SCHEDULED_CHANGE';
