-- V10's CREATE TABLE IF NOT EXISTS leaves pre-existing tables unchanged.
-- Restore defaults required by native inserts without rewriting existing message data.
ALTER TABLE message_conversations
    ALTER COLUMN id SET DEFAULT gen_random_uuid(),
    ALTER COLUMN type SET DEFAULT 'DIRECT',
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET DEFAULT NOW();

ALTER TABLE message_participants
    ALTER COLUMN id SET DEFAULT gen_random_uuid(),
    ALTER COLUMN joined_at SET DEFAULT NOW();

ALTER TABLE direct_messages
    ALTER COLUMN id SET DEFAULT gen_random_uuid(),
    ALTER COLUMN created_at SET DEFAULT NOW();

ALTER TABLE direct_message_attachments
    ALTER COLUMN id SET DEFAULT gen_random_uuid(),
    ALTER COLUMN sort_order SET DEFAULT 0,
    ALTER COLUMN created_at SET DEFAULT NOW();
