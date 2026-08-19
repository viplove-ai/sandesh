-- Sandesh — everything this service stores, which is deliberately almost nothing.
--
-- There is no conversations table and no members table: membership is derived from Nirman's
-- contract views on every read. There is no messages table: an undelivered message waits in
-- `outbox` and is deleted the moment the recipient's device says it holds it.
--
-- What is left is a spool, an idempotency ledger, and the settings a device needs to be
-- notified. None of it is a chat history and none of it can be queried as one.

-- ------------------------------------------------------------------ the spool
-- One row per recipient per message. Fan-out happens on write against the member list as it
-- stood at that instant, so somebody posted to the site tomorrow was not on the list and
-- receives nothing — which is the retention policy expressed as a join rather than a rule.
CREATE TABLE outbox (
    id            uuid PRIMARY KEY,
    recipient_id  uuid        NOT NULL,
    sender_id     uuid        NOT NULL,
    org_id        uuid        NOT NULL,
    conv_id       varchar(120) NOT NULL,
    msg_id        uuid        NOT NULL,
    client_msg_id uuid        NOT NULL,
    kind          varchar(16) NOT NULL,
    body          text,
    media         jsonb,
    sent_at       timestamptz NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_outbox_kind CHECK (kind IN ('TEXT', 'IMAGE', 'DOC')),
    -- Redelivery after a crash must not produce a second row for the same person.
    CONSTRAINT uq_outbox_recipient_msg UNIQUE (recipient_id, msg_id)
);

-- The only hot query: "what did this device miss?", ordered so Last-Event-ID can resume.
CREATE INDEX ix_outbox_recipient ON outbox (recipient_id, sent_at, msg_id);
-- The nightly sweep.
CREATE INDEX ix_outbox_created ON outbox (created_at);

COMMENT ON TABLE outbox IS
    'Undelivered messages only. A row is deleted when the recipient device acknowledges it has '
    'committed the message to its own storage; anything nobody collects is swept after 7 days. '
    'This is not a chat history and must never be read as one.';

-- ------------------------------------------------------------------ idempotency
-- The id is minted on the device, so a phone on a bad link can send the same message three
-- times without producing three messages. Carries no body: it is a ledger, not a copy.
CREATE TABLE message_idempotency (
    client_msg_id uuid PRIMARY KEY,
    sender_id     uuid        NOT NULL,
    msg_id        uuid        NOT NULL,
    conv_id       varchar(120) NOT NULL,
    sent_at       timestamptz NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_idempotency_created ON message_idempotency (created_at);

-- ------------------------------------------------------------------ media
-- What was uploaded, so a download can be authorised without trusting the client's claim about
-- which conversation an object belongs to. The object itself lives in MinIO with its own
-- lifecycle rule.
CREATE TABLE media_object (
    id           uuid PRIMARY KEY,
    uploader_id  uuid        NOT NULL,
    org_id       uuid        NOT NULL,
    conv_id      varchar(120),
    object_key   varchar(200) NOT NULL,
    content_type varchar(100) NOT NULL,
    size_bytes   bigint      NOT NULL,
    file_name    varchar(255) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_media_created ON media_object (created_at);

-- ------------------------------------------------------------------ notifications
CREATE TABLE push_subscription (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL,
    endpoint   text        NOT NULL,
    p256dh     varchar(255) NOT NULL,
    auth       varchar(255) NOT NULL,
    user_agent varchar(400),
    created_at timestamptz NOT NULL DEFAULT now(),
    last_ok_at timestamptz,

    -- One row per endpoint. A browser re-subscribing with the same endpoint is the same device.
    CONSTRAINT uq_push_endpoint UNIQUE (endpoint)
);

CREATE INDEX ix_push_user ON push_subscription (user_id);
