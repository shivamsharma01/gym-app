-- The placeholder plan given to members imported from devices was called "Unknown".
UPDATE membership_plan
SET name = 'Fallback Membership plan for quick access'
WHERE name = 'Unknown'
  AND description = 'Placeholder plan for members imported from devices; replace with the correct plan later.';
