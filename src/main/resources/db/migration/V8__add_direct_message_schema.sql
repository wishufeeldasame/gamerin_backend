CREATE TABLE IF NOT EXISTS message_conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type VARCHAR(20) NOT NULL DEFAULT 'DIRECT',
    direct_key VARCHAR(100) UNIQUE,
    last_message_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT chk_message_conversations_type CHECK (type IN ('DIRECT'))
);

CREATE TABLE IF NOT EXISTS message_participants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES message_conversations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    last_read_at TIMESTAMPTZ,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (conversation_id, user_id)
);

CREATE TABLE IF NOT EXISTS direct_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES message_conversations(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content TEXT,
    shared_post_id UUID REFERENCES posts(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT chk_direct_messages_content CHECK (
        content IS NOT NULL OR shared_post_id IS NOT NULL
    )
);

CREATE TABLE IF NOT EXISTS direct_message_attachments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID NOT NULL REFERENCES direct_messages(id) ON DELETE CASCADE,
    attachment_type VARCHAR(20) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_url TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_direct_message_attachments_type CHECK (attachment_type IN ('IMAGE', 'VIDEO')),
    CONSTRAINT uq_direct_message_attachments_sort UNIQUE (message_id, sort_order)
);

CREATE INDEX IF NOT EXISTS idx_message_conversations_updated
    ON message_conversations(updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_message_participants_user_conversation
    ON message_participants(user_id, conversation_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_message_participants_conversation
    ON message_participants(conversation_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_direct_messages_conversation_created
    ON direct_messages(conversation_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_direct_messages_sender_created
    ON direct_messages(sender_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_direct_messages_shared_post
    ON direct_messages(shared_post_id)
    WHERE shared_post_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_direct_message_attachments_message_sort
    ON direct_message_attachments(message_id, sort_order);
