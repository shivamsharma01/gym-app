-- Refunds are no longer recorded. A refunded row was already excluded from completed totals;
-- FAILED keeps that and remains a status the application can load.
UPDATE payment SET status = 'FAILED' WHERE status = 'REFUNDED';
