-- Tier 2 retention (docs/PLAN.md §15), and it ships switched off.
--
-- This is the one feature in the product that is gated on something other than engineering:
-- retention needs a stated purpose, a stated window, and language in the employment contract
-- before the first retained message exists. `app.retention.enabled` defaults to false and the
-- table below stays empty until somebody sets it deliberately.
--
-- What is retained, and what is not:
--
--   Tier 1  system and actionable messages  -- already a Nirman record; not stored here
--   Tier 2  site, project and announcements -- retained here, disclosed, time-boxed
--   Tier 3  direct messages                 -- never retained, not by any setting
--
-- The tier is decided by the conversation id and enforced in code rather than by trusting the
-- caller: a `dm:` conversation cannot reach this table, and no configuration makes it possible.

CREATE TABLE retained_message (
    msg_id       uuid PRIMARY KEY,
    org_id       uuid        NOT NULL,
    conv_id      varchar(120) NOT NULL,
    sender_id    uuid        NOT NULL,
    kind         varchar(16) NOT NULL,
    body         text,
    media        jsonb,
    sent_at      timestamptz NOT NULL,
    -- When the retention job may delete it. Written at insert from the configured window rather
    -- than computed at read: a window that changes must not silently un-delete last year's
    -- messages or bring forward the deletion of this year's.
    retain_until timestamptz NOT NULL,

    CONSTRAINT ck_retained_kind CHECK (kind IN ('TEXT', 'IMAGE', 'DOC')),
    -- Belt and braces against the thing that would be worst: a direct message in here. The
    -- service refuses it too; this is the constraint that survives a bug in the service.
    CONSTRAINT ck_retained_not_direct CHECK (conv_id NOT LIKE 'dm:%')
);

-- The re-sync query: one conversation, a date window, oldest first.
CREATE INDEX ix_retained_conv ON retained_message (conv_id, sent_at);
-- The deletion job.
CREATE INDEX ix_retained_until ON retained_message (retain_until);

COMMENT ON TABLE retained_message IS
    'Tier 2 only — site, project and announcement channels. Never direct messages. No screen '
    'renders this to an administrator: reading it is an export, which is logged and announced '
    'in the channel it came from. See docs/PLAN.md §15.';

-- ------------------------------------------------------------------ the export gate
-- Retained and browsable are different decisions, and conflating them is what makes staff
-- abandon a messenger. Every read of the retained store passes through here: two named
-- approvers, a stated reason, a bounded scope — and the channel is told it happened.
CREATE TABLE retention_export (
    id             uuid PRIMARY KEY,
    org_id         uuid        NOT NULL,
    conv_id        varchar(120) NOT NULL,
    requested_by   uuid        NOT NULL,
    approved_by    uuid,
    reason         varchar(1000) NOT NULL,
    from_date      date        NOT NULL,
    to_date        date        NOT NULL,
    requested_at   timestamptz NOT NULL DEFAULT now(),
    approved_at    timestamptz,
    executed_at    timestamptz,
    -- The notice posted into the conversation. Nullable only until the export is executed.
    announced_at   timestamptz,

    -- The second approver must be a different person. An export one administrator can perform
    -- alone is not a gate, it is a screen with extra steps.
    CONSTRAINT ck_export_two_people CHECK (approved_by IS NULL OR approved_by <> requested_by)
);

CREATE INDEX ix_retention_export_org ON retention_export (org_id, requested_at DESC);
