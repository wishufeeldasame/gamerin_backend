package com.gamerin.backend.domain.message.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.message.dto.request.CreateConversationRequest;
import com.gamerin.backend.domain.message.dto.request.UpdateMessageRequest;
import com.gamerin.backend.domain.message.dto.response.ConversationResponse;
import com.gamerin.backend.domain.message.dto.response.MessageResponse;
import com.gamerin.backend.domain.message.entity.DirectMessage;
import com.gamerin.backend.domain.message.entity.MessageConversation;
import com.gamerin.backend.domain.message.entity.MessageParticipant;
import com.gamerin.backend.domain.message.repository.DirectMessageAttachmentRepository;
import com.gamerin.backend.domain.message.repository.DirectMessageRepository;
import com.gamerin.backend.domain.message.repository.MessageConversationRepository;
import com.gamerin.backend.domain.message.repository.MessageParticipantRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PostRepository postRepository;

    @Mock
    private MessageConversationRepository messageConversationRepository;

    @Mock
    private MessageParticipantRepository messageParticipantRepository;

    @Mock
    private DirectMessageRepository directMessageRepository;

    @Mock
    private DirectMessageAttachmentRepository directMessageAttachmentRepository;

    @Mock
    private MessageAttachmentStorageService messageAttachmentStorageService;

    @Mock
    private MessageRealtimeService messageRealtimeService;

    private MessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(
                userRepository,
                postRepository,
                messageConversationRepository,
                messageParticipantRepository,
                directMessageRepository,
                directMessageAttachmentRepository,
                messageAttachmentStorageService,
                new MessageResponseAssembler(),
                messageRealtimeService
        );
    }

    @Test
    void updateMessageEditsOwnMessageAndReturnsEditedAt() {
        User viewer = user("viewer");
        User recipient = user("recipient");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        MessageParticipant recipientParticipant = participant(conversation, recipient);
        DirectMessage message = message(conversation, viewer, "before");

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(Optional.of(viewerParticipant));
        when(directMessageRepository.findByIdAndConversationIdAndDeletedAtIsNull(
                message.getId(),
                conversation.getId()
        )).thenReturn(Optional.of(message));
        when(messageParticipantRepository.findByConversationId(conversation.getId()))
                .thenReturn(List.of(viewerParticipant, recipientParticipant));
        when(messageParticipantRepository.findByConversationIdAndDeletedAtIsNull(conversation.getId()))
                .thenReturn(List.of(viewerParticipant, recipientParticipant));
        when(directMessageAttachmentRepository.findByMessageIds(List.of(message.getId()))).thenReturn(List.of());

        MessageResponse response = messageService.updateMessage(
                CustomUserPrincipal.from(viewer),
                conversation.getId(),
                message.getId(),
                new UpdateMessageRequest("after")
        );

        assertThat(message.getContent()).isEqualTo("after");
        assertThat(message.getEditedAt()).isNotNull();
        assertThat(response.text()).isEqualTo("after");
        assertThat(response.editedAt()).isEqualTo(message.getEditedAt());
    }

    @Test
    void updateMessageRejectsOtherUsersMessage() {
        User viewer = user("viewer");
        User sender = user("sender");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        DirectMessage message = message(conversation, sender, "before");

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(Optional.of(viewerParticipant));
        when(directMessageRepository.findByIdAndConversationIdAndDeletedAtIsNull(
                message.getId(),
                conversation.getId()
        )).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> messageService.updateMessage(
                CustomUserPrincipal.from(viewer),
                conversation.getId(),
                message.getId(),
                new UpdateMessageRequest("after")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.FORBIDDEN.value());

        assertThat(message.getContent()).isEqualTo("before");
    }

    @Test
    void deleteMessageSoftDeletesOwnMessage() {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        DirectMessage message = message(conversation, viewer, "message");

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(Optional.of(viewerParticipant));
        when(directMessageRepository.findByIdAndConversationIdAndDeletedAtIsNull(
                message.getId(),
                conversation.getId()
        )).thenReturn(Optional.of(message));
        when(messageParticipantRepository.findByConversationIdAndDeletedAtIsNull(conversation.getId()))
                .thenReturn(List.of(viewerParticipant));

        messageService.deleteMessage(CustomUserPrincipal.from(viewer), conversation.getId(), message.getId());

        assertThat(message.getDeletedAt()).isNotNull();
    }

    @Test
    void leaveConversationSoftDeletesOnlyViewerParticipant() {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(Optional.of(viewerParticipant));

        messageService.leaveConversation(CustomUserPrincipal.from(viewer), conversation.getId());

        assertThat(viewerParticipant.getDeletedAt()).isNotNull();
        assertThat(viewerParticipant.getClearedAt()).isNotNull();
    }

    @Test
    void getConversationsStillShowsRecipientWhenRecipientHasLeft() {
        User viewer = user("viewer");
        User recipient = user("recipient");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        MessageParticipant recipientParticipant = participant(conversation, recipient);
        recipientParticipant.softDelete();

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.findActiveByUserIdWithConversation(viewer.getId()))
                .thenReturn(List.of(viewerParticipant));
        when(messageParticipantRepository.findByConversationId(conversation.getId()))
                .thenReturn(List.of(viewerParticipant, recipientParticipant));
        when(directMessageRepository.findRecentActiveByConversationId(any(), any())).thenReturn(List.of());

        List<ConversationResponse> responses = messageService.getConversations(CustomUserPrincipal.from(viewer));

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().recipient().id()).isEqualTo(recipient.getId());
    }

    @Test
    void createConversationReactivatesDeletedParticipant() {
        User viewer = user("viewer");
        User recipient = user("recipient");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        viewerParticipant.softDelete();
        OffsetDateTime clearedAt = viewerParticipant.getClearedAt();
        MessageParticipant recipientParticipant = participant(conversation, recipient);

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(userRepository.findByIdAndDeletedAtIsNull(recipient.getId())).thenReturn(Optional.of(recipient));
        when(messageConversationRepository.findByDirectKeyAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(conversation));
        when(messageParticipantRepository.findByConversationIdAndUserId(conversation.getId(), viewer.getId()))
                .thenReturn(Optional.of(viewerParticipant));
        when(messageParticipantRepository.findByConversationIdAndUserId(conversation.getId(), recipient.getId()))
                .thenReturn(Optional.of(recipientParticipant));
        when(messageParticipantRepository.findByConversationId(conversation.getId()))
                .thenReturn(List.of(viewerParticipant, recipientParticipant));
        when(directMessageRepository.findRecentActiveByConversationIdAfter(any(), any(), any())).thenReturn(List.of());

        messageService.createConversation(
                CustomUserPrincipal.from(viewer),
                new CreateConversationRequest(null, recipient.getId())
        );

        assertThat(viewerParticipant.getDeletedAt()).isNull();
        assertThat(viewerParticipant.getClearedAt()).isEqualTo(clearedAt);
        assertThat(viewerParticipant.getLastReadAt()).isNotNull();
        verify(messageParticipantRepository, never()).save(any(MessageParticipant.class));
    }

    private User user(String handle) {
        User user = User.createLocal(handle + "@example.com", handle, handle, "encoded-password");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private MessageConversation conversation() {
        MessageConversation conversation = MessageConversation.createDirect(UUID.randomUUID() + ":" + UUID.randomUUID());
        ReflectionTestUtils.setField(conversation, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(conversation, "createdAt", OffsetDateTime.now());
        ReflectionTestUtils.setField(conversation, "updatedAt", OffsetDateTime.now());
        return conversation;
    }

    private MessageParticipant participant(MessageConversation conversation, User user) {
        MessageParticipant participant = MessageParticipant.create(conversation, user);
        ReflectionTestUtils.setField(participant, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(participant, "joinedAt", OffsetDateTime.now());
        return participant;
    }

    private DirectMessage message(MessageConversation conversation, User sender, String content) {
        DirectMessage message = DirectMessage.create(conversation, sender, content, null);
        ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(message, "createdAt", OffsetDateTime.now());
        return message;
    }
}
