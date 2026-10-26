package com.gamerin.backend.domain.message.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.gamerin.backend.domain.message.entity.DirectMessage;
import com.gamerin.backend.domain.message.entity.DirectMessageAttachment;
import com.gamerin.backend.domain.message.entity.MessageAttachmentType;
import com.gamerin.backend.domain.message.entity.MessageConversation;
import com.gamerin.backend.domain.message.entity.MessageParticipant;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DirectMessageAttachmentRepositoryTest {

    private static final OffsetDateTime CLEARED_AT = OffsetDateTime.parse("2026-10-20T10:00:00+09:00");

    @Autowired
    private DirectMessageAttachmentRepository directMessageAttachmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findAccessibleActiveByIdRejectsAttachmentsSentBeforeParticipantClearedConversation() {
        User viewer = saveUser("attachmentviewer");
        User sender = saveUser("attachmentsender");
        MessageConversation conversation = MessageConversation.createDirect("attachment-cleared-at");
        entityManager.persist(conversation);
        MessageParticipant viewerParticipant = MessageParticipant.create(conversation, viewer);
        entityManager.persist(viewerParticipant);
        entityManager.persist(MessageParticipant.create(conversation, sender));

        DirectMessageAttachment beforeClear = saveAttachment(conversation, sender, CLEARED_AT.minusMinutes(1));
        DirectMessageAttachment atClear = saveAttachment(conversation, sender, CLEARED_AT);
        DirectMessageAttachment afterClear = saveAttachment(conversation, sender, CLEARED_AT.plusMinutes(1));
        flushAndClear();

        // Without clearedAt every active attachment in the conversation is accessible.
        assertThat(directMessageAttachmentRepository.findAccessibleActiveById(beforeClear.getId(), viewer.getId()))
                .isPresent();

        // Leave and rejoin: clearedAt stays set while deletedAt is cleared again.
        entityManager.createNativeQuery("UPDATE message_participants SET cleared_at = :clearedAt WHERE id = :id")
                .setParameter("clearedAt", CLEARED_AT)
                .setParameter("id", viewerParticipant.getId())
                .executeUpdate();
        flushAndClear();

        assertThat(directMessageAttachmentRepository.findAccessibleActiveById(beforeClear.getId(), viewer.getId()))
                .isEmpty();
        assertThat(directMessageAttachmentRepository.findAccessibleActiveById(atClear.getId(), viewer.getId()))
                .isEmpty();
        assertThat(directMessageAttachmentRepository.findAccessibleActiveById(afterClear.getId(), viewer.getId()))
                .isPresent();
        assertThat(directMessageAttachmentRepository.findAccessibleActiveById(beforeClear.getId(), sender.getId()))
                .isPresent();
    }

    private DirectMessageAttachment saveAttachment(
            MessageConversation conversation,
            User sender,
            OffsetDateTime createdAt
    ) {
        DirectMessage message = DirectMessage.create(conversation, sender, null, null);
        entityManager.persist(message);
        DirectMessageAttachment attachment = DirectMessageAttachment.create(
                message,
                MessageAttachmentType.IMAGE,
                "image.jpg",
                "/uploads/message-attachments/image.jpg",
                0
        );
        entityManager.persist(attachment);
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE direct_messages SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", message.getId())
                .executeUpdate();
        return attachment;
    }

    private User saveUser(String handle) {
        return userRepository.saveAndFlush(User.createLocal(
                handle + "@example.com",
                handle,
                handle,
                "encoded-password"
        ));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
