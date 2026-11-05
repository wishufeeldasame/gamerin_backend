-- DM 전송 재시도 멱등 키. 기존 메시지와 키 없이 보낸 메시지는 NULL이라 충돌하지 않는다.
ALTER TABLE direct_messages
    ADD COLUMN client_message_id UUID;

CREATE UNIQUE INDEX uk_direct_messages_sender_client_message_id
    ON direct_messages (sender_id, client_message_id)
    WHERE client_message_id IS NOT NULL;
