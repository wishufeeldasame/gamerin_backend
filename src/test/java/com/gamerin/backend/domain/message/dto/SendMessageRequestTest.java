package com.gamerin.backend.domain.message.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.gamerin.backend.domain.message.dto.request.SendMessageRequest;

class SendMessageRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsOptionalClientMessageId() throws Exception {
        UUID clientMessageId = UUID.randomUUID();

        SendMessageRequest withKey = objectMapper.readValue(
                "{\"content\":\"hello\",\"clientMessageId\":\"" + clientMessageId + "\"}",
                SendMessageRequest.class
        );
        SendMessageRequest withoutKey = objectMapper.readValue("{\"content\":\"hello\"}", SendMessageRequest.class);

        assertThat(withKey.clientMessageId()).isEqualTo(clientMessageId);
        assertThat(withoutKey.content()).isEqualTo("hello");
        assertThat(withoutKey.clientMessageId()).isNull();
    }

    @Test
    void rejectsClientMessageIdThatIsNotUuid() {
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"content\":\"hello\",\"clientMessageId\":\"not-a-uuid\"}",
                SendMessageRequest.class
        )).isInstanceOf(InvalidFormatException.class);
    }
}
