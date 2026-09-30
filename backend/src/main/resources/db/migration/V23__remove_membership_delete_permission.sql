-- Memberships are ended with Cancel, which stops door access and keeps the history.
-- Deleting the permission removes it from every role (role_permission cascades).

DELETE
FROM permission
WHERE name = 'MEMBERSHIP_DELETE';
