-- The action channel (docs/PLAN.md §16).
--
-- Nirman gains no ability to act here and Sandesh gains none there. What crosses the boundary
-- is a card: a title, a line of text, and a list of things the recipient may choose to do —
-- each of which is a request the *device* makes to Nirman, with the user's own token, so
-- @PreAuthorize, SiteAccessGuard and PeriodLockGuard all run exactly as they do in Nirman.
--
-- A service account that approves expenses on a user's behalf is an authorisation bypass with a
-- friendly name. It is worth restating in the schema because this is the table that would have
-- to exist for one.

ALTER TABLE outbox DROP CONSTRAINT ck_outbox_kind;
ALTER TABLE outbox ADD CONSTRAINT ck_outbox_kind
    CHECK (kind IN ('TEXT', 'IMAGE', 'DOC', 'SYSTEM'));

-- The buttons. Each entry carries a label, an HTTP method and a path on Nirman's API — never a
-- full URL, so a card cannot point a signed-in phone at somebody else's host.
ALTER TABLE outbox ADD COLUMN actions jsonb;

ALTER TABLE retained_message DROP CONSTRAINT ck_retained_kind;
ALTER TABLE retained_message ADD CONSTRAINT ck_retained_kind
    CHECK (kind IN ('TEXT', 'IMAGE', 'DOC', 'SYSTEM'));
ALTER TABLE retained_message ADD COLUMN actions jsonb;

COMMENT ON COLUMN outbox.actions IS
    'What the recipient may do about this card. Paths on Nirman''s API, executed by the device '
    'with the user''s own token — never by this service. See docs/PLAN.md §16.';
