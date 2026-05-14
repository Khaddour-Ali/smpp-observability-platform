-- W3C traceparent header (<= 55 chars) for sync->async trace continuation via RabbitMQ
ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS trace_context VARCHAR(128);
