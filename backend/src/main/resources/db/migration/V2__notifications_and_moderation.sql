-- Week 4 of the pilot: the notifications Nirman never had, and the controls an administrator
-- needs once more than ten people are using this.
--
-- Still no chat history. Everything here is about reaching a device or refusing one.

-- ------------------------------------------------------------------ per-user preferences
-- One row per user, created lazily on first change. Absent means the defaults, which are the
-- ones somebody would pick: notify me, show me who and roughly what, no quiet hours.
CREATE TABLE notify_settings (
    user_id          uuid PRIMARY KEY,
    previews_enabled boolean     NOT NULL DEFAULT true,
    -- Local wall-clock, in the org's timezone. A supervisor asleep at 23:00 does not want the
    -- store's stock query, and the alternative to this is that he turns notifications off for
    -- good and misses the one that mattered.
    quiet_from       time,
    quiet_to         time,
    muted_conv_ids   text[]      NOT NULL DEFAULT '{}',
    updated_at       timestamptz NOT NULL DEFAULT now(),

    -- Both or neither. A window with one end is not a window, and the check is cheaper than
    -- the branch that would otherwise have to guess what a half-set one means.
    CONSTRAINT ck_quiet_hours_are_a_pair
        CHECK ((quiet_from IS NULL) = (quiet_to IS NULL))
);

-- ------------------------------------------------------------------ restrictions
-- Muting or blocking somebody in the messenger, which is a different act from deactivating
-- them in Nirman. Deactivation there already closes this — chat_directory_v carries is_active
-- and session_epoch — so this table is only for the person who still works here and may not
-- use the messenger.
CREATE TABLE chat_restriction (
    user_id       uuid PRIMARY KEY,
    org_id        uuid        NOT NULL,
    level         varchar(10) NOT NULL,
    -- Not nullable, deliberately. A user who cannot find out why they are locked out telephones
    -- a supervisor, who telephones the administrator, who has forgotten.
    reason        varchar(400) NOT NULL,
    restricted_by uuid        NOT NULL,
    restricted_at timestamptz NOT NULL DEFAULT now(),
    until         timestamptz,

    CONSTRAINT ck_restriction_level CHECK (level IN ('MUTED', 'BLOCKED'))
);

COMMENT ON TABLE chat_restriction IS
    'MUTED may read and not send; BLOCKED may not connect. Lives here rather than in Nirman '
    'because it is chat-specific state, and putting it there would mean this service needs '
    'write access to Nirman — the boundary the whole design is built on keeping.';

-- ------------------------------------------------------------------ audit
-- An administrator may go anywhere and never silently. This is the half that survives them:
-- readable by every admin in the org, not only the one who wrote the row.
CREATE TABLE chat_audit (
    id         uuid PRIMARY KEY,
    org_id     uuid        NOT NULL,
    actor_id   uuid        NOT NULL,
    action     varchar(40) NOT NULL,
    subject_id uuid,
    detail     jsonb,
    at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_chat_audit_org ON chat_audit (org_id, at DESC);

-- ------------------------------------------------------------------ reports
-- The input side of blocking. Without it, an administrator only reaches for the control after
-- somebody telephones them about it.
CREATE TABLE chat_report (
    id          uuid PRIMARY KEY,
    org_id      uuid        NOT NULL,
    reporter_id uuid        NOT NULL,
    subject_id  uuid,
    conv_id     varchar(120),
    -- What was said, as the reporter saw it. This is the one place a message body is kept, and
    -- it is kept because somebody deliberately handed it over — which is a different act from
    -- the server retaining it behind their back.
    quoted_body text,
    note        varchar(1000),
    created_at  timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz
);

CREATE INDEX ix_chat_report_org ON chat_report (org_id, created_at DESC);
