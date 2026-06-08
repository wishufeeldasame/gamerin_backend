package com.gamerin.backend.domain.message.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamerin.backend.domain.message.dto.request.CreateConversationRequest;
import com.gamerin.backend.domain.message.dto.request.SendMessageRequest;
import com.gamerin.backend.domain.message.dto.response.ConversationResponse;
import com.gamerin.backend.domain.message.entity.DirectMessage;
import com.gamerin.backend.domain.message.entity.MessageConversation;
import com.gamerin.backend.domain.message.entity.MessageParticipant;
import com.gamerin.backend.domain.message.repository.DirectMessageAttachmentRepository;
import com.gamerin.backend.domain.message.repository.DirectMessageRepository;
import com.gamerin.backend.domain.message.repository.MessageConversationRepository;
import com.gamerin.backend.domain.message.repository.MessageParticipantRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.post.service.LightweightSecurityScanService;
import com.gamerin.backend.domain.post.service.MediaUploadSecurityService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.jwt.SseStreamTokenService;
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

    @Mock
    private MediaUploadSecurityService mediaUploadSecurityService;

    @Mock
    private LightweightSecurityScanService lightweightSecurityScanService;

    @Mock
    private SseStreamTokenService sseStreamTokenService;

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
                messageRealtimeService,
                mediaUploadSecurityService,
                lightweightSecurityScanService,
                sseStreamTokenService
        );
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
    void deleteMessagePublishesAfterTransactionCommit() {
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

        TransactionSynchronizationManager.initSynchronization();
        try {
            messageService.deleteMessage(CustomUserPrincipal.from(viewer), conversation.getId(), message.getId());

            verify(messageRealtimeService, never()).publish(any(), any());
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);

            synchronizations.getFirst().afterCommit();
            verify(messageRealtimeService).publish(eq(viewer.getId()), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
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

    @Test
    void createConversationDoesNotReactivateDeletedRecipient() {
        User viewer = user("viewer");
        User recipient = user("recipient");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        MessageParticipant recipientParticipant = participant(conversation, recipient);
        recipientParticipant.softDelete();
        OffsetDateTime recipientClearedAt = recipientParticipant.getClearedAt();

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(userRepository.findByIdAndDeletedAtIsNull(recipient.getId())).thenReturn(Optional.of(recipient));
        when(messageConversationRepository.findByDirectKeyAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(conversation));
        when(messageParticipantRepository.findByConversationIdAndUserId(conversation.getId(), viewer.getId()))
                .thenReturn(Optional.of(viewerParticipant));
        when(messageParticipantRepository.findByConversationId(conversation.getId()))
                .thenReturn(List.of(viewerParticipant, recipientParticipant));
        when(directMessageRepository.findRecentActiveByConversationId(any(), any())).thenReturn(List.of());

        messageService.createConversation(
                CustomUserPrincipal.from(viewer),
                new CreateConversationRequest(null, recipient.getId())
        );

        assertThat(recipientParticipant.getDeletedAt()).isNotNull();
        assertThat(recipientParticipant.getClearedAt()).isEqualTo(recipientClearedAt);
        assertThat(recipientParticipant.getLastReadAt()).isNull();
    }

    @Test
    void sendMessageStoresEmptyContentForSharedPostOnlyMessage() {
        User viewer = user("viewer");
        User recipient = user("recipient");
        Post sharedPost = post(viewer);
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        MessageParticipant recipientParticipant = participant(conversation, recipient);

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageConversationRepository.findByIdAndDeletedAtIsNull(conversation.getId()))
                .thenReturn(Optional.of(conversation));
        when(messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(Optional.of(viewerParticipant));
        when(postRepository.findByIdAndDeletedAtIsNull(sharedPost.getId())).thenReturn(Optional.of(sharedPost));
        when(messageParticipantRepository.findByConversationId(conversation.getId()))
                .thenReturn(List.of(viewerParticipant, recipientParticipant));
        when(directMessageRepository.save(any(DirectMessage.class)))
                .thenAnswer(invocation -> {
                    DirectMessage message = invocation.getArgument(0);
                    ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
                    ReflectionTestUtils.setField(message, "createdAt", OffsetDateTime.now());
                    return message;
                });
        when(messageParticipantRepository.findByConversationIdAndDeletedAtIsNull(conversation.getId()))
                .thenReturn(List.of(viewerParticipant, recipientParticipant));
        when(directMessageAttachmentRepository.findByMessageIds(any())).thenReturn(List.of());

        messageService.sendMessage(
                CustomUserPrincipal.from(viewer),
                conversation.getId(),
                new SendMessageRequest(null, sharedPost.getId())
        );

        ArgumentCaptor<DirectMessage> messageCaptor = ArgumentCaptor.forClass(DirectMessage.class);
        verify(directMessageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("");
    }

    @Test
    void getMessagesUsesCreatedAtAndIdCursor() {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        OffsetDateTime cursorCreatedAt = OffsetDateTime.now();
        UUID cursorId = UUID.randomUUID();

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(Optional.of(viewerParticipant));
        when(directMessageRepository.findActivePageByConversationIdBefore(
                eq(conversation.getId()),
                eq(cursorCreatedAt),
                eq(cursorId),
                any()
        )).thenReturn(List.of());
        when(messageParticipantRepository.findByConversationId(conversation.getId())).thenReturn(List.of(viewerParticipant));

        messageService.getMessages(
                CustomUserPrincipal.from(viewer),
                conversation.getId(),
                cursorCreatedAt + "|" + cursorId,
                30
        );

        verify(directMessageRepository).findActivePageByConversationIdBefore(
                eq(conversation.getId()),
                eq(cursorCreatedAt),
                eq(cursorId),
                any()
        );
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

    private Post post(User author) {
        Post post = Post.create(author, "post");
        ReflectionTestUtils.setField(post, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(post, "createdAt", OffsetDateTime.now());
        ReflectionTestUtils.setField(post, "updatedAt", OffsetDateTime.now());
        return post;
    }
}
