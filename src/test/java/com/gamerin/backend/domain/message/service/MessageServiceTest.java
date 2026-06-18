package com.gamerin.backend.domain.message.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
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
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.message.dto.request.CreateConversationRequest;
import com.gamerin.backend.domain.message.dto.request.SendMessageRequest;
import com.gamerin.backend.domain.message.dto.request.SendMultipartMessageRequest;
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
                videoOptimizationService
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
        when(directMessageAttachmentRepository.findByMessageIds(List.of(message.getId()))).thenReturn(List.of());

        messageService.deleteMessage(CustomUserPrincipal.from(viewer), conversation.getId(), message.getId());

        assertThat(message.getDeletedAt()).isNotNull();
    }

    @Test
    void deleteMessageImmediatelyDeletesAttachmentFiles() {
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

        messageService.deleteMessage(CustomUserPrincipal.from(viewer), conversation.getId(), message.getId());

        assertThat(message.getDeletedAt()).isNotNull();
        verify(messageAttachmentStorageService).deletePublicUrlQuietly("/uploads/message-attachments/screenshot.jpg");
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
    void sendMessageRejectsWhenTextModerationFlagsContentBeforeSaving() {
        User viewer = user("viewer");
        MessageConversation conversation = conversation();
        MessageParticipant viewerParticipant = participant(conversation, viewer);

        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageConversationRepository.findByIdAndDeletedAtIsNull(conversation.getId()))
                .thenReturn(Optional.of(conversation));
        when(messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(Optional.of(viewerParticipant));
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
        verify(directMessageRepository, never()).save(any(DirectMessage.class));
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
        when(messageConversationRepository.findByIdAndDeletedAtIsNull(conversation.getId()))
                .thenReturn(Optional.of(conversation));
        when(messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(Optional.of(viewerParticipant));
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
        when(messageConversationRepository.findByIdAndDeletedAtIsNull(conversation.getId()))
                .thenReturn(Optional.of(conversation));
        when(messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(Optional.of(viewerParticipant));
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
        when(directMessageAttachmentRepository.findByIdWithMessage(attachment.getId())).thenReturn(Optional.of(attachment));
        when(messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getId(),
                viewer.getId()
        )).thenReturn(Optional.of(viewerParticipant));
        when(messageAttachmentStorageService.resolvePublicUrl("/uploads/message-attachments/screenshot.jpg"))
                .thenReturn(Optional.of(storedPath));

        MessageService.MessageAttachmentFile attachmentFile =
                messageService.getMessageAttachmentFile(CustomUserPrincipal.from(viewer), attachment.getId());

        assertThat(attachmentFile.path()).isEqualTo(storedPath);
        assertThat(attachmentFile.fileName()).isEqualTo("screenshot.png");
        assertThat(attachmentFile.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void getMessageAttachmentFileRejectsDeletedMessage() {
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
        when(directMessageAttachmentRepository.findByIdWithMessage(attachment.getId())).thenReturn(Optional.of(attachment));

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

    private void stubMultipartMessageSend(
            User viewer,
            MessageConversation conversation,
            MessageParticipant viewerParticipant,
            MessageParticipant recipientParticipant
    ) {
        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
        when(messageConversationRepository.findByIdAndDeletedAtIsNull(conversation.getId()))
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
