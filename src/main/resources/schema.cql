-- ULIDs are stored as 16-byte, big-endian blobs. See docs/identifiers.md.
CREATE KEYSPACE IF NOT EXISTS chatapp
    WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

USE chatapp;

CREATE TABLE IF NOT EXISTS chats (
    chat_id blob PRIMARY KEY,
    name text,
    type text,
    created_at timestamp
);

CREATE TABLE IF NOT EXISTS messages_by_chat (
    chat_id blob,
    created_at timestamp,
    message_id blob,
    sender_id blob,
    content_type text,
    body text,
    media_bucket text,
    media_key text,
    media_version_id text,
    media_size_bytes bigint,
    PRIMARY KEY (chat_id, created_at, message_id)
) WITH CLUSTERING ORDER BY (created_at DESC, message_id DESC);

CREATE TABLE IF NOT EXISTS chats_by_user (
    user_id blob,
    last_message_at timestamp,
    chat_id blob,
    chat_name text,
    last_message_preview text,
    PRIMARY KEY (user_id, last_message_at, chat_id)
) WITH CLUSTERING ORDER BY (last_message_at DESC, chat_id DESC);

CREATE TABLE IF NOT EXISTS participants_by_chat (
    chat_id blob,
    user_id blob,
    joined_at timestamp,
    role text,
    PRIMARY KEY (chat_id, user_id)
);

CREATE TABLE IF NOT EXISTS read_state_by_user (
    user_id blob,
    chat_id blob,
    last_read_message_id blob,
    last_read_at timestamp,
    PRIMARY KEY (user_id, chat_id)
);

CREATE TABLE IF NOT EXISTS devices_by_user (
    user_id blob,
    device_id text,
    push_token text,
    last_seen_at timestamp,
    PRIMARY KEY (user_id, device_id)
);

-- Durable idempotency keys. No TTL: retries retain their canonical message identity.
CREATE TABLE IF NOT EXISTS send_reservations (
    chat_id blob,
    sender_id blob,
    client_message_id blob,
    message_id blob,
    created_at timestamp,
    payload_hash text,
    PRIMARY KEY ((chat_id, sender_id), client_message_id)
);

-- Upload authorization expires independently of objects referenced by messages.
CREATE TABLE IF NOT EXISTS media_uploads (
    upload_id blob PRIMARY KEY,
    user_id blob,
    chat_id blob,
    bucket text,
    object_key text,
    size_bytes bigint,
    content_type text,
    expires_at timestamp
);
