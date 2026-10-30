package com.gamerin.backend.domain.message.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.message.dto.request.CreateConversationRequest;
import com.gamerin.backend.domain.message.dto.request.SendMessageRequest;
import com.gamerin.backend.domain.message.dto.request.SendMultipartMessageRequest;
import com.gamerin.backend.domain.message.dto.request.SharePostMessageRequest;
import com.gamerin.backend.domain.message.dto.response.ConversationResponse;
import com.gamerin.backend.domain.message.entity.DirectMessage;
import com.gamerin.backend.domain.message.entity.DirectMessageAttachment;
import com.gamerin.backend.domain.message.entity.MessageAttachmentType;
import com.gamerin.backend.domain.message.entity.MessageConversation;
import com.gamerin.backend.domain.message.entity.MessageParticipant;
import com.gamerin.backend.domain.message.repository.DirectMessageAttachmentRepository;
import com.gamerin.backend.domain.message.repository.DirectMessageRepository;
import com.gamerin.backend.domain.message.repository.MessageConversationRepository;
import com.gamerin.backend.domain.message.repository.MessageParticipantRepository;
import com.gamerin.backend.domain.notification.service.NotificationCommandService;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.moderation.ContentModerationService;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.post.service.LightweightSecurityScanService;
import com.gamerin.backend.domain.post.service.MediaStorageService;
import com.gamerin.backend.domain.post.service.MediaUploadSecurityService;
import com.gamerin.backend.domain.post.service.TextSecurityService;
import com.gamerin.backend.domain.post.service.VideoMetadataService;
import com.gamerin.backend.domain.post.service.VideoOptimizationService;
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
    private ContentModerationService contentModerationService;

    @Mock
    private MediaUploadSecurityService mediaUploadSecurityService;

    @Mock
    private LightweightSecurityScanService lightweightSecurityScanService;

    @Mock
    private TextSecurityService textSecurityService;

    @Mock
    private VideoMetadataService videoMetadataService;

    @Mock
    private VideoOptimizationService videoOptimizationService;

    @Mock
    private SseStreamTokenService sseStreamTokenService;

    @Mock
    private NotificationCommandService notificationCommandService;

    @Mock
    private MembershipService membershipService;

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
                contentModerationService,
                mediaUploadSecurityService,
                lightweightSecurityScanService,
                textSecurityService,
                videoMetadataService,
                videoOptimizationService,
                sseStreamTokenService,
                notificationCommandService,
                membershipService
        );
    }

    @Test
    void deleteMessageSoftDeletesOwnMessage() {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        DirectMessage message = message(conversation, viewer, "message");

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageConversationRepository.findActiveByIdForUpdate(conversation.getId()))
                .thenReturn(Optional.of(conversation));
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
        when(directMessageAttachmentRepository.findByMessageIds(List.of(message.getId()))).thenReturn(List.of());

        messageService.deleteMessage(CustomUserPrincipal.from(viewer), conversation.getId(), message.getId());

        assertThat(message.getDeletedAt()).isNotNull();
    }

    @Test
    void deleteMessageDeletesAttachmentFilesAndPublishesEventAfterCommit() {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        DirectMessage message = message(conversation, viewer, "message");
        DirectMessageAttachment attachment = attachment(
                message,
                MessageAttachmentType.IMAGE,
                "screenshot.png",
                "/uploads/message-attachments/screenshot.jpg"
        );

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageConversationRepository.findActiveByIdForUpdate(conversation.getId()))
                .thenReturn(Optional.of(conversation));
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
        when(directMessageAttachmentRepository.findByMessageIds(List.of(message.getId()))).thenReturn(List.of(attachment));

        TransactionSynchronizationManager.initSynchronization();
        try {
            messageService.deleteMessage(CustomUserPrincipal.from(viewer), conversation.getId(), message.getId());

            assertThat(message.getDeletedAt()).isNotNull();
            verify(messageAttachmentStorageService, never())
                    .deletePublicUrlQuietly("/uploads/message-attachments/screenshot.jpg");
            verify(messageRealtimeService, never()).publish(any(), any());

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(2);

            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(messageAttachmentStorageService).deletePublicUrlQuietly("/uploads/message-attachments/screenshot.jpg");
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
        when(messageConversationRepository.findActiveByIdForUpdate(conversation.getId()))
                .thenReturn(Optional.of(conversation));
        when(messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(Optional.of(viewerParticipant));

        messageService.leaveConversation(CustomUserPrincipal.from(viewer), conversation.getId());

        assertThat(viewerParticipant.getDeletedAt()).isNotNull();
        assertThat(viewerParticipant.getClearedAt()).isNotNull();
    }

    @Test
    void issueStreamTokenDelegatesToSseTokenServiceForAuthenticatedUser() {
        User viewer = user("viewer");
        SseStreamTokenService.IssuedToken issuedToken = new SseStreamTokenService.IssuedToken(
                "stream-token",
                java.time.Instant.now().plusSeconds(60)
        );

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(sseStreamTokenService.issue(viewer.getId())).thenReturn(issuedToken);

        SseStreamTokenService.IssuedToken response = messageService.issueStreamToken(CustomUserPrincipal.from(viewer));

        assertThat(response).isEqualTo(issuedToken);
        verify(sseStreamTokenService).issue(viewer.getId());
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
    void getConversationsEnrichesNestedRecipientsWithOneMembershipLookup() throws Exception {
        User viewer = user("viewer");
        User activeRecipient = user("active");
        User inactiveRecipient = user("inactive");
        MessageConversation first = conversation();
        MessageConversation second = conversation();
        MessageParticipant firstViewer = participant(first, viewer);
        MessageParticipant secondViewer = participant(second, viewer);
        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.findActiveByUserIdWithConversation(viewer.getId()))
                .thenReturn(List.of(firstViewer, secondViewer));
        when(messageParticipantRepository.findByConversationId(first.getId()))
                .thenReturn(List.of(firstViewer, participant(first, activeRecipient)));
        when(messageParticipantRepository.findByConversationId(second.getId()))
                .thenReturn(List.of(secondViewer, participant(second, inactiveRecipient)));
        when(membershipService.findActiveUserIds(List.of(activeRecipient.getId(), inactiveRecipient.getId())))
                .thenReturn(Set.of(activeRecipient.getId()));

        List<ConversationResponse> responses = messageService.getConversations(CustomUserPrincipal.from(viewer));

        assertThat(responses).extracting(response -> response.recipient().membershipBadge())
                .containsExactly(true, false);
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(responses);
        assertThat(json.get(0).get("recipient").get("membershipBadge").booleanValue()).isTrue();
        assertThat(json.get(1).get("recipient").get("membershipBadge").booleanValue()).isFalse();
        assertThat(json.get(0).has("participants")).isFalse();
        assertThat(responses).extracting(ConversationResponse::id).containsExactly(first.getId(), second.getId());
        verify(membershipService).findActiveUserIds(List.of(activeRecipient.getId(), inactiveRecipient.getId()));
        verify(membershipService, never()).isActive(any());
    }

    @Test
    void getConversationsSkipsMembershipLookupForEmptyResult() {
        User viewer = user("viewer");
        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));

        assertThat(messageService.getConversations(CustomUserPrincipal.from(viewer))).isEmpty();

        verifyNoInteractions(membershipService);
    }

    @Test
    void searchRecipientsAddsMembershipBadgesWithOneLookup() {
        User viewer = user("viewer");
        User active = user("active");
        User inactive = user("inactive");
        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(userRepository.searchMessageRecipients(eq(viewer.getId()), eq("recipient"), any()))
                .thenReturn(List.of(active, inactive));
        when(membershipService.findActiveUserIds(List.of(active.getId(), inactive.getId())))
                .thenReturn(Set.of(active.getId()));

        var responses = messageService.searchRecipients(CustomUserPrincipal.from(viewer), " recipient ", 20);

        assertThat(responses).extracting(response -> response.membershipBadge()).containsExactly(true, false);
        assertThat(responses).extracting(response -> response.id()).containsExactly(active.getId(), inactive.getId());
        verify(membershipService).findActiveUserIds(List.of(active.getId(), inactive.getId()));
        verify(membershipService, never()).isActive(any());
    }

    @Test
    void searchRecipientsSkipsMembershipLookupForEmptyResult() {
        User viewer = user("viewer");
        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));

        assertThat(messageService.searchRecipients(CustomUserPrincipal.from(viewer), "unknown", 20)).isEmpty();

        verifyNoInteractions(membershipService);
    }

    @Test
    void sharePostEnrichesRecipientsInRequestedOrderWithOneMembershipLookup() {
        User viewer = user("viewer");
        User active = user("active");
        User inactive = user("inactive");
        Post sharedPost = post(viewer);
        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(postRepository.findByIdAndDeletedAtIsNull(sharedPost.getId())).thenReturn(Optional.of(sharedPost));
        for (User recipient : List.of(active, inactive)) {
            MessageConversation conversation = conversation();
            MessageParticipant viewerParticipant = participant(conversation, viewer);
            MessageParticipant recipientParticipant = participant(conversation, recipient);
            List<MessageParticipant> participants = List.of(viewerParticipant, recipientParticipant);
            List<String> orderedIds = new java.util.ArrayList<>(List.of(viewer.getId().toString(), recipient.getId().toString()));
            orderedIds.sort(String::compareTo);
            when(userRepository.findByIdAndDeletedAtIsNull(recipient.getId())).thenReturn(Optional.of(recipient));
            when(messageConversationRepository.findActiveByDirectKeyForUpdate(String.join(":", orderedIds)))
                    .thenReturn(Optional.of(conversation));
            when(messageParticipantRepository.findByConversationIdAndUserId(conversation.getId(), viewer.getId()))
                    .thenReturn(Optional.of(viewerParticipant));
            when(messageParticipantRepository.findByConversationId(conversation.getId())).thenReturn(participants);
            when(messageParticipantRepository.findByConversationIdAndDeletedAtIsNull(conversation.getId()))
                    .thenReturn(participants);
        }
        when(directMessageRepository.save(any(DirectMessage.class))).thenAnswer(invocation -> {
            DirectMessage saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(saved, "createdAt", OffsetDateTime.now());
            return saved;
        });
        when(membershipService.findActiveUserIds(List.of(inactive.getId(), active.getId())))
                .thenReturn(Set.of(active.getId()));

        var responses = messageService.sharePost(CustomUserPrincipal.from(viewer),
                new SharePostMessageRequest(sharedPost.getId(), null, List.of(inactive.getId(), active.getId()), null));

        assertThat(responses).extracting(response -> response.recipient().id())
                .containsExactly(inactive.getId(), active.getId());
        assertThat(responses).extracting(response -> response.recipient().membershipBadge())
                .containsExactly(false, true);
        verify(membershipService).findActiveUserIds(List.of(inactive.getId(), active.getId()));
        verify(membershipService, never()).isActive(any());
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
        when(messageConversationRepository.findActiveByDirectKeyForUpdate(any()))
                .thenReturn(Optional.of(conversation));
        when(messageParticipantRepository.findByConversationIdAndUserId(conversation.getId(), viewer.getId()))
                .thenReturn(Optional.of(viewerParticipant));
        when(messageParticipantRepository.findByConversationId(conversation.getId()))
                .thenReturn(List.of(viewerParticipant, recipientParticipant));
        when(directMessageRepository.findRecentActiveByConversationIdAfter(any(), any(), any())).thenReturn(List.of());
        when(membershipService.isActive(recipient.getId())).thenReturn(true);

        ConversationResponse response = messageService.createConversation(
                CustomUserPrincipal.from(viewer),
                new CreateConversationRequest(null, recipient.getId())
        );

        assertThat(viewerParticipant.getDeletedAt()).isNull();
        assertThat(viewerParticipant.getClearedAt()).isEqualTo(clearedAt);
        assertThat(viewerParticipant.getLastReadAt()).isNotNull();
        assertThat(response.recipient().membershipBadge()).isTrue();
        verify(membershipService).isActive(recipient.getId());
        verify(messageConversationRepository).insertDirectConversationIfAbsent(any());
        verify(messageParticipantRepository).insertParticipantIfAbsent(conversation.getId(), viewer.getId());
        verify(messageParticipantRepository).insertParticipantIfAbsent(conversation.getId(), recipient.getId());
        verify(messageConversationRepository, never()).save(any(MessageConversation.class));
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
        when(messageConversationRepository.findActiveByDirectKeyForUpdate(any()))
                .thenReturn(Optional.of(conversation));
        when(messageParticipantRepository.findByConversationIdAndUserId(conversation.getId(), viewer.getId()))
                .thenReturn(Optional.of(viewerParticipant));
        when(messageParticipantRepository.findByConversationId(conversation.getId()))
                .thenReturn(List.of(viewerParticipant, recipientParticipant));
        when(directMessageRepository.findRecentActiveByConversationId(any(), any())).thenReturn(List.of());

        ConversationResponse response = messageService.createConversation(
                CustomUserPrincipal.from(viewer),
                new CreateConversationRequest(null, recipient.getId())
        );

        assertThat(recipientParticipant.getDeletedAt()).isNotNull();
        assertThat(recipientParticipant.getClearedAt()).isEqualTo(recipientClearedAt);
        assertThat(recipientParticipant.getLastReadAt()).isNull();
        assertThat(response.recipient().membershipBadge()).isFalse();
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
        when(messageParticipantRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(true);
        when(messageConversationRepository.findActiveByIdForUpdate(conversation.getId()))
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

        TransactionSynchronizationManager.initSynchronization();
        try {
            messageService.sendMessage(
                    CustomUserPrincipal.from(viewer),
                    conversation.getId(),
                    new SendMessageRequest(null, sharedPost.getId())
            );

            ArgumentCaptor<DirectMessage> messageCaptor = ArgumentCaptor.forClass(DirectMessage.class);
            verify(directMessageRepository).save(messageCaptor.capture());
            assertThat(messageCaptor.getValue().getContent()).isEqualTo("");
            assertThat(messageCaptor.getValue().getClientMessageId()).isNull();
            verify(directMessageRepository, never()).findBySenderIdAndClientMessageId(any(), any());
            verify(messageRealtimeService, never()).publish(any(), any());

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(2);

            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(messageRealtimeService).publish(eq(viewer.getId()), any());
            verify(messageRealtimeService).publish(eq(recipient.getId()), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void sendMessageRejectsWhenTextModerationFlagsContentBeforeSaving() {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Content violates moderation policy: harassment"))
                .when(contentModerationService)
                .assertTextAllowed("bad message");

        assertThatThrownBy(() -> messageService.sendMessage(
                CustomUserPrincipal.from(viewer),
                conversation.getId(),
                new SendMessageRequest(" bad message ", null)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());

        verify(textSecurityService).assertTextSafe("bad message");
        verify(messageParticipantRepository, never()).findByConversationIdAndUserIdAndDeletedAtIsNull(any(), any());
        verify(messageConversationRepository, never()).findActiveByIdForUpdate(any());
        verify(directMessageRepository, never()).save(any(DirectMessage.class));
    }

    @Test
    void sendMessageRejectsNonParticipantBeforeModerationAndLock() {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(false);

        assertThatThrownBy(() -> messageService.sendMessage(
                CustomUserPrincipal.from(viewer),
                conversation.getId(),
                new SendMessageRequest("hello", null)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.FORBIDDEN.value());

        verify(contentModerationService, never()).assertTextAllowed(any());
        verify(messageConversationRepository, never()).findActiveByIdForUpdate(any());
        verify(directMessageRepository, never()).save(any(DirectMessage.class));
    }

    @Test
    void sendMessageLoadsParticipantEntityOnlyOnceAfterModerationAndLock() {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(true);
        when(messageConversationRepository.findActiveByIdForUpdate(conversation.getId()))
                .thenReturn(Optional.of(conversation));
        when(messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(Optional.of(viewerParticipant));
        when(messageParticipantRepository.findByConversationId(conversation.getId()))
                .thenReturn(List.of(viewerParticipant));
        when(directMessageRepository.save(any(DirectMessage.class)))
                .thenAnswer(invocation -> {
                    DirectMessage message = invocation.getArgument(0);
                    ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
                    ReflectionTestUtils.setField(message, "createdAt", OffsetDateTime.now());
                    return message;
                });
        when(messageParticipantRepository.findByConversationIdAndDeletedAtIsNull(conversation.getId()))
                .thenReturn(List.of(viewerParticipant));
        when(directMessageAttachmentRepository.findByMessageIds(any())).thenReturn(List.of());

        messageService.sendMessage(
                CustomUserPrincipal.from(viewer),
                conversation.getId(),
                new SendMessageRequest("hello", null)
        );

        // 잠금 전에 참여자 엔티티를 올리면 검열 중 변경이 잠금 뒤 조회에 반영되지 않는다.
        InOrder inOrder = inOrder(messageParticipantRepository, contentModerationService, messageConversationRepository);
        inOrder.verify(messageParticipantRepository)
                .existsByConversationIdAndUserIdAndDeletedAtIsNull(conversation.getId(), viewer.getId());
        inOrder.verify(contentModerationService).assertTextAllowed("hello");
        inOrder.verify(messageConversationRepository).findActiveByIdForUpdate(conversation.getId());
        inOrder.verify(messageParticipantRepository)
                .findByConversationIdAndUserIdAndDeletedAtIsNull(conversation.getId(), viewer.getId());
        verify(messageParticipantRepository)
                .findByConversationIdAndUserIdAndDeletedAtIsNull(conversation.getId(), viewer.getId());
    }

    @Test
    void sendMessageRetryWithSameClientMessageIdReturnsSavedMessageAndModeratesOnce() {
        User viewer = user("viewer");
        User recipient = user("recipient");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        MessageParticipant recipientParticipant = participant(conversation, recipient);
        UUID clientMessageId = UUID.randomUUID();
        AtomicReference<DirectMessage> savedMessage = new AtomicReference<>();

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(true);
        when(messageConversationRepository.findActiveByIdForUpdate(conversation.getId()))
                .thenReturn(Optional.of(conversation));
        when(messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(Optional.of(viewerParticipant));
        when(directMessageRepository.findBySenderIdAndClientMessageId(viewer.getId(), clientMessageId))
                .thenAnswer(invocation -> Optional.ofNullable(savedMessage.get()));
        when(messageParticipantRepository.findByConversationId(conversation.getId()))
                .thenReturn(List.of(viewerParticipant, recipientParticipant));
        when(directMessageRepository.save(any(DirectMessage.class)))
                .thenAnswer(invocation -> {
                    DirectMessage message = invocation.getArgument(0);
                    ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
                    ReflectionTestUtils.setField(message, "createdAt", OffsetDateTime.now());
                    savedMessage.set(message);
                    return message;
                });
        when(messageParticipantRepository.findByConversationIdAndDeletedAtIsNull(conversation.getId()))
                .thenReturn(List.of(viewerParticipant, recipientParticipant));
        when(directMessageAttachmentRepository.findByMessageIds(any())).thenReturn(List.of());

        SendMessageRequest request = new SendMessageRequest(" hello ", null, clientMessageId);
        var first = messageService.sendMessage(CustomUserPrincipal.from(viewer), conversation.getId(), request);
        var retried = messageService.sendMessage(CustomUserPrincipal.from(viewer), conversation.getId(), request);

        assertThat(retried.id()).isEqualTo(first.id());
        assertThat(first.clientMessageId()).isEqualTo(clientMessageId);
        assertThat(retried.clientMessageId()).isEqualTo(clientMessageId);
        verify(directMessageRepository).save(any(DirectMessage.class));
        // 검열은 대화 행 잠금보다 먼저 수행된다.
        InOrder inOrder = inOrder(contentModerationService, messageConversationRepository);
        inOrder.verify(contentModerationService).assertTextAllowed("hello");
        inOrder.verify(messageConversationRepository).findActiveByIdForUpdate(conversation.getId());
        verify(textSecurityService).assertTextSafe("hello");
        verify(contentModerationService).assertTextAllowed("hello");
        verify(notificationCommandService).createOrRefreshDirectMessage(any(), any(), any(), any());
        verify(messageRealtimeService, times(2)).publish(any(), any());
    }

    @Test
    void sendMessageReturnsExistingMessageWhenSameClientMessageIdSavedDuringModeration() {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        UUID clientMessageId = UUID.randomUUID();
        DirectMessage concurrentMessage = DirectMessage.create(conversation, viewer, "hello", null, clientMessageId);
        ReflectionTestUtils.setField(concurrentMessage, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(concurrentMessage, "createdAt", OffsetDateTime.now());

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(true);
        when(messageConversationRepository.findActiveByIdForUpdate(conversation.getId()))
                .thenReturn(Optional.of(conversation));
        when(messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(Optional.of(viewerParticipant));
        // 잠금 전에는 없고 검열 중 다른 요청이 저장해 잠금 뒤에는 있다.
        when(directMessageRepository.findBySenderIdAndClientMessageId(viewer.getId(), clientMessageId))
                .thenReturn(Optional.empty(), Optional.of(concurrentMessage));
        when(messageParticipantRepository.findByConversationId(conversation.getId()))
                .thenReturn(List.of(viewerParticipant));
        when(directMessageAttachmentRepository.findByMessageIds(List.of(concurrentMessage.getId())))
                .thenReturn(List.of());

        var response = messageService.sendMessage(
                CustomUserPrincipal.from(viewer),
                conversation.getId(),
                new SendMessageRequest("hello", null, clientMessageId)
        );

        assertThat(response.id()).isEqualTo(concurrentMessage.getId());
        verify(contentModerationService).assertTextAllowed("hello");
        verify(directMessageRepository, never()).save(any(DirectMessage.class));
        verify(messageRealtimeService, never()).publish(any(), any());
    }

    @Test
    void sendMessageRejectsClientMessageIdUsedInAnotherConversation() {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        UUID clientMessageId = UUID.randomUUID();
        DirectMessage otherConversationMessage = DirectMessage.create(conversation(), viewer, "hello", null, clientMessageId);

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(true);
        when(directMessageRepository.findBySenderIdAndClientMessageId(viewer.getId(), clientMessageId))
                .thenReturn(Optional.of(otherConversationMessage));

        assertThatThrownBy(() -> messageService.sendMessage(
                CustomUserPrincipal.from(viewer),
                conversation.getId(),
                new SendMessageRequest("hello", null, clientMessageId)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());

        verifyNoInteractions(contentModerationService);
        verify(directMessageRepository, never()).save(any(DirectMessage.class));
    }

    @Test
    void sendMessageMapsConcurrentClientMessageIdCollisionToConflict() {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        UUID clientMessageId = UUID.randomUUID();

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(true);
        when(messageConversationRepository.findActiveByIdForUpdate(conversation.getId()))
                .thenReturn(Optional.of(conversation));
        when(messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(Optional.of(viewerParticipant));
        when(messageParticipantRepository.findByConversationId(conversation.getId()))
                .thenReturn(List.of(viewerParticipant));
        when(directMessageRepository.save(any(DirectMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uk_direct_messages_sender_client_message_id\""
        )).when(directMessageRepository).flush();

        assertThatThrownBy(() -> messageService.sendMessage(
                CustomUserPrincipal.from(viewer),
                conversation.getId(),
                new SendMessageRequest("hello", null, clientMessageId)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());
        verify(notificationCommandService, never()).createOrRefreshDirectMessage(any(), any(), any(), any());
    }

    @Test
    void sendMultipartMessageRetryWithSameClientMessageIdSkipsValidationAndStorage() {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        UUID clientMessageId = UUID.randomUUID();
        DirectMessage sentMessage = DirectMessage.create(conversation, viewer, "screenshot", null, clientMessageId);
        ReflectionTestUtils.setField(sentMessage, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(sentMessage, "createdAt", OffsetDateTime.now());
        MockMultipartFile imageFile = new MockMultipartFile(
                "attachments",
                "screenshot.png",
                "image/png",
                "image".getBytes()
        );

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(true);
        when(directMessageRepository.findBySenderIdAndClientMessageId(viewer.getId(), clientMessageId))
                .thenReturn(Optional.of(sentMessage));
        when(messageParticipantRepository.findByConversationId(conversation.getId()))
                .thenReturn(List.of(viewerParticipant));
        when(directMessageAttachmentRepository.findByMessageIds(List.of(sentMessage.getId()))).thenReturn(List.of());

        SendMultipartMessageRequest request = new SendMultipartMessageRequest();
        request.setContent("screenshot");
        request.setAttachments(List.of(imageFile));
        request.setClientMessageId(clientMessageId);

        var response = messageService.sendMultipartMessage(CustomUserPrincipal.from(viewer), conversation.getId(), request);

        assertThat(response.id()).isEqualTo(sentMessage.getId());
        assertThat(response.clientMessageId()).isEqualTo(clientMessageId);
        verifyNoInteractions(contentModerationService, mediaUploadSecurityService, messageAttachmentStorageService);
        verify(messageConversationRepository, never()).findActiveByIdForUpdate(any());
        verify(directMessageRepository, never()).save(any(DirectMessage.class));
    }

    @Test
    void messageResponseExposesClientMessageIdOnlyToSender() {
        User sender = user("sender");
        User recipient = user("recipient");
        MessageConversation conversation = conversation();
        List<MessageParticipant> participants = List.of(participant(conversation, sender), participant(conversation, recipient));
        UUID clientMessageId = UUID.randomUUID();
        DirectMessage message = DirectMessage.create(conversation, sender, "hello", null, clientMessageId);
        ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(message, "createdAt", OffsetDateTime.now());
        MessageResponseAssembler assembler = new MessageResponseAssembler();

        assertThat(assembler.toMessage(message, sender.getId(), List.of(), participants).clientMessageId())
                .isEqualTo(clientMessageId);
        assertThat(assembler.toMessage(message, recipient.getId(), List.of(), participants).clientMessageId())
                .isNull();
    }

    @Test
    void sendMultipartMessageStoresImageAttachmentAsPreparedJpeg() throws Exception {
        User viewer = user("viewer");
        User recipient = user("recipient");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        MessageParticipant recipientParticipant = participant(conversation, recipient);
        MockMultipartFile imageFile = new MockMultipartFile(
                "attachments",
                "screenshot.png",
                "image/png",
                "image".getBytes()
        );
        MediaStorageService.PreparedMediaFile preparedImage =
                new MediaStorageService.PreparedMediaFile("compressed".getBytes(), ".jpg");
        MessageAttachmentStorageService.StoredFile storedFile = new MessageAttachmentStorageService.StoredFile(
                Path.of("uploads/message-attachments/screenshot.jpg"),
                "http://localhost/uploads/message-attachments/screenshot.jpg"
        );

        stubMultipartMessageSend(viewer, conversation, viewerParticipant, recipientParticipant);
        when(mediaUploadSecurityService.prepareImage(imageFile)).thenReturn(preparedImage);
        when(messageAttachmentStorageService.store(preparedImage)).thenReturn(storedFile);

        SendMultipartMessageRequest request = new SendMultipartMessageRequest();
        request.setContent(" screenshot ");
        request.setAttachments(List.of(imageFile));

        messageService.sendMultipartMessage(CustomUserPrincipal.from(viewer), conversation.getId(), request);

        verify(textSecurityService).assertTextSafe("screenshot");
        verify(contentModerationService).assertMessageAllowed("screenshot", List.of(imageFile));
        verify(mediaUploadSecurityService).assertImageFileSafe(imageFile);
        verify(lightweightSecurityScanService).assertFileClean(imageFile);
        verify(mediaUploadSecurityService).prepareImage(imageFile);
        verify(messageAttachmentStorageService).store(preparedImage);

        // 참여자 엔티티는 잠금 뒤에 한 번만 조회한다.
        InOrder inOrder = inOrder(messageParticipantRepository, contentModerationService, messageConversationRepository);
        inOrder.verify(messageParticipantRepository)
                .existsByConversationIdAndUserIdAndDeletedAtIsNull(conversation.getId(), viewer.getId());
        inOrder.verify(contentModerationService).assertMessageAllowed("screenshot", List.of(imageFile));
        inOrder.verify(messageConversationRepository).findActiveByIdForUpdate(conversation.getId());
        inOrder.verify(messageParticipantRepository)
                .findByConversationIdAndUserIdAndDeletedAtIsNull(conversation.getId(), viewer.getId());
        verify(messageParticipantRepository)
                .findByConversationIdAndUserIdAndDeletedAtIsNull(conversation.getId(), viewer.getId());

        ArgumentCaptor<List<DirectMessageAttachment>> attachmentCaptor = ArgumentCaptor.forClass(List.class);
        verify(directMessageAttachmentRepository).saveAll(attachmentCaptor.capture());
        DirectMessageAttachment attachment = attachmentCaptor.getValue().getFirst();
        assertThat(attachment.getAttachmentType()).isEqualTo(MessageAttachmentType.IMAGE);
        assertThat(attachment.getFileName()).isEqualTo("screenshot.png");
        assertThat(attachment.getFileUrl()).endsWith("/screenshot.jpg");
    }

    @Test
    void sendMultipartMessageRejectsFakeImageBeforeMessageAndFileStorage() throws Exception {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        MockMultipartFile fakeImage = new MockMultipartFile(
                "attachments",
                "payload.html",
                "image/jpeg",
                "<script>alert(1)</script>".getBytes()
        );

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image file must be JPEG or PNG."))
                .when(mediaUploadSecurityService)
                .assertImageFileSafe(fakeImage);

        SendMultipartMessageRequest request = new SendMultipartMessageRequest();
        request.setAttachments(List.of(fakeImage));

        assertThatThrownBy(() -> messageService.sendMultipartMessage(
                CustomUserPrincipal.from(viewer),
                conversation.getId(),
                request
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());

        verify(lightweightSecurityScanService, never()).assertFileClean(fakeImage);
        verify(contentModerationService, never()).assertMessageAllowed(any(), anyList());
        verify(directMessageRepository, never()).save(any(DirectMessage.class));
        verify(messageAttachmentStorageService, never()).store(any(MediaStorageService.PreparedMediaFile.class));
        verify(messageAttachmentStorageService, never()).store(any(MediaStorageService.PreparedMediaPath.class));
    }

    @Test
    void sendMultipartMessageRejectsWhenModerationFlagsAttachmentBeforeStorage() throws Exception {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        MockMultipartFile imageFile = new MockMultipartFile(
                "attachments",
                "blocked.jpg",
                "image/jpeg",
                "image".getBytes()
        );

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Content violates moderation policy: sexual"))
                .when(contentModerationService)
                .assertMessageAllowed(eq("blocked"), anyList());

        SendMultipartMessageRequest request = new SendMultipartMessageRequest();
        request.setContent(" blocked ");
        request.setAttachments(List.of(imageFile));

        assertThatThrownBy(() -> messageService.sendMultipartMessage(
                CustomUserPrincipal.from(viewer),
                conversation.getId(),
                request
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());

        verify(mediaUploadSecurityService).assertImageFileSafe(imageFile);
        verify(lightweightSecurityScanService).assertFileClean(imageFile);
        verify(mediaUploadSecurityService, never()).prepareImage(any());
        verify(directMessageRepository, never()).save(any(DirectMessage.class));
        verify(messageAttachmentStorageService, never()).store(any(MediaStorageService.PreparedMediaFile.class));
        verify(messageAttachmentStorageService, never()).store(any(MediaStorageService.PreparedMediaPath.class));
    }

    @Test
    void sendMultipartMessageStoresVideoAttachmentThroughOptimizationFlow() throws Exception {
        User viewer = user("viewer");
        User recipient = user("recipient");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        MessageParticipant recipientParticipant = participant(conversation, recipient);
        MockMultipartFile videoFile = new MockMultipartFile(
                "attachments",
                "clip.mp4",
                "video/mp4",
                new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}
        );
        MediaStorageService.PreparedMediaPath preparedVideo =
                new MediaStorageService.PreparedMediaPath(Path.of("build/test-message-video.mp4"), ".mp4");
        MessageAttachmentStorageService.StoredFile storedFile = new MessageAttachmentStorageService.StoredFile(
                Path.of("uploads/message-attachments/clip.mp4"),
                "http://localhost/uploads/message-attachments/clip.mp4"
        );

        stubMultipartMessageSend(viewer, conversation, viewerParticipant, recipientParticipant);
        when(videoMetadataService.readDurationSeconds(videoFile)).thenReturn(60.0);
        when(videoOptimizationService.prepareVideo(videoFile)).thenReturn(preparedVideo);
        when(messageAttachmentStorageService.store(preparedVideo)).thenReturn(storedFile);

        SendMultipartMessageRequest request = new SendMultipartMessageRequest();
        request.setAttachments(List.of(videoFile));

        messageService.sendMultipartMessage(CustomUserPrincipal.from(viewer), conversation.getId(), request);

        verify(mediaUploadSecurityService).assertVideoFileSafe(videoFile);
        verify(lightweightSecurityScanService).assertFileClean(videoFile);
        verify(videoMetadataService).readDurationSeconds(videoFile);
        verify(contentModerationService).assertMessageAllowed(null, List.of(videoFile));
        verify(videoOptimizationService).prepareVideo(videoFile);
        verify(messageAttachmentStorageService).store(preparedVideo);

        ArgumentCaptor<List<DirectMessageAttachment>> attachmentCaptor = ArgumentCaptor.forClass(List.class);
        verify(directMessageAttachmentRepository).saveAll(attachmentCaptor.capture());
        DirectMessageAttachment attachment = attachmentCaptor.getValue().getFirst();
        assertThat(attachment.getAttachmentType()).isEqualTo(MessageAttachmentType.VIDEO);
        assertThat(attachment.getFileName()).isEqualTo("clip.mp4");
        assertThat(attachment.getFileUrl()).endsWith("/clip.mp4");
    }

    @Test
    void getMessageAttachmentFileAllowsActiveConversationParticipant() {
        User viewer = user("viewer");
        User recipient = user("recipient");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        DirectMessage message = message(conversation, recipient, "message");
        DirectMessageAttachment attachment = attachment(
                message,
                MessageAttachmentType.IMAGE,
                "screenshot.png",
                "/uploads/message-attachments/screenshot.jpg"
        );
        Path storedPath = Path.of("build.gradle").toAbsolutePath();

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(directMessageAttachmentRepository.findAccessibleActiveById(attachment.getId(), viewer.getId()))
                .thenReturn(Optional.of(attachment));
        when(messageAttachmentStorageService.resolvePublicUrl("/uploads/message-attachments/screenshot.jpg"))
                .thenReturn(Optional.of(storedPath));

        MessageService.MessageAttachmentFile attachmentFile =
                messageService.getMessageAttachmentFile(CustomUserPrincipal.from(viewer), attachment.getId());

        assertThat(attachmentFile.path()).isEqualTo(storedPath);
        assertThat(attachmentFile.fileName()).isEqualTo("screenshot.png");
        assertThat(attachmentFile.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void getMessageAttachmentFileRejectsInaccessibleOrDeletedMessage() {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();
        DirectMessage message = message(conversation, viewer, "message");
        message.softDelete();
        DirectMessageAttachment attachment = attachment(
                message,
                MessageAttachmentType.IMAGE,
                "screenshot.png",
                "/uploads/message-attachments/screenshot.jpg"
        );

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(directMessageAttachmentRepository.findAccessibleActiveById(attachment.getId(), viewer.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> messageService.getMessageAttachmentFile(
                CustomUserPrincipal.from(viewer),
                attachment.getId()
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());

        verify(messageAttachmentStorageService, never()).resolvePublicUrl(any());
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

    @Test
    void messageResponseUsesPlaceholderForDeletedSharedPost() {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);
        Post sharedPost = post(viewer);
        sharedPost.softDelete();
        DirectMessage message = DirectMessage.create(conversation, viewer, "", sharedPost);
        ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(message, "createdAt", OffsetDateTime.now());

        var response = new MessageResponseAssembler().toMessage(
                message,
                viewer.getId(),
                List.of(),
                List.of(viewerParticipant)
        );

        assertThat(response.sharedPost()).isNotNull();
        assertThat(response.sharedPost().author()).isEqualTo("삭제된 게시글");
        assertThat(response.sharedPost().authorHandle()).isEmpty();
        assertThat(response.sharedPost().content()).isEqualTo("삭제된 게시글");
    }

    private void stubMultipartMessageSend(
            User viewer,
            MessageConversation conversation,
            MessageParticipant viewerParticipant,
            MessageParticipant recipientParticipant
    ) {
        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageParticipantRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(true);
        when(messageConversationRepository.findActiveByIdForUpdate(conversation.getId()))
                .thenReturn(Optional.of(conversation));
        when(messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(Optional.of(viewerParticipant));
        when(messageParticipantRepository.findByConversationId(conversation.getId()))
                .thenReturn(List.of(viewerParticipant, recipientParticipant));
        when(messageParticipantRepository.findByConversationIdAndDeletedAtIsNull(conversation.getId()))
                .thenReturn(List.of(viewerParticipant, recipientParticipant));
        when(directMessageRepository.save(any(DirectMessage.class)))
                .thenAnswer(invocation -> {
                    DirectMessage message = invocation.getArgument(0);
                    ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
                    ReflectionTestUtils.setField(message, "createdAt", OffsetDateTime.now());
                    return message;
                });
        when(directMessageAttachmentRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(directMessageAttachmentRepository.findByMessageIds(any())).thenReturn(List.of());
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

    private DirectMessageAttachment attachment(
            DirectMessage message,
            MessageAttachmentType type,
            String fileName,
            String fileUrl
    ) {
        DirectMessageAttachment attachment = DirectMessageAttachment.create(message, type, fileName, fileUrl, 0);
        ReflectionTestUtils.setField(attachment, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(attachment, "createdAt", OffsetDateTime.now());
        return attachment;
    }

    private Post post(User author) {
        Post post = Post.create(author, "post");
        ReflectionTestUtils.setField(post, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(post, "createdAt", OffsetDateTime.now());
        ReflectionTestUtils.setField(post, "updatedAt", OffsetDateTime.now());
        return post;
    }
}
