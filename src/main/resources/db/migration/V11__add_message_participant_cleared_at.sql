ALTER TABLE message_participants
    ADD COLUMN IF NOT EXISTS cleared_at TIMESTAMPTZ;
