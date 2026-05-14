CREATE TABLE messages (
    message_id VARCHAR(128) NOT NULL PRIMARY KEY,
    system_id VARCHAR(64) NOT NULL,
    source_addr VARCHAR(64) NOT NULL,
    destination_addr VARCHAR(64) NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    queued_at TIMESTAMPTZ NULL,
    processed_at TIMESTAMPTZ NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_messages_status ON
messages (status);

CREATE INDEX idx_messages_received_at ON
messages (received_at);

CREATE INDEX idx_messages_processed_at ON
messages (processed_at);

CREATE INDEX idx_messages_status_updated_at ON
messages (status,
updated_at);
