package com.gamerin.backend.domain.message.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.message.dto.request.CreateConversationRequest;
import com.gamerin.backend.domain.message.dto.request.SendMessageRequest;
import com.gamerin.backend.domain.message.dto.request.SendMultipartMessageRequest;
import com.gamerin.backend.domain.message.dto.request.SharePostMessageRequest;
import com.gamerin.backend.domain.message.dto.response.ConversationResponse;
import com.gamerin.backend.domain.message.dto.response.MessageRecipientResponse;
import com.gamerin.backend.domain.message.dto.response.MessageRealtimeEvent;
import com.gamerin.backend.domain.message.dto.response.MessageResponse;
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
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.jwt.SseStreamTokenService;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@Transactional
public class MessageService {

    private static final int DEFAULT_MESSAGE_PAGE_SIZE = 30;
    private static final int DEFAULT_RECIPIENT_SEARCH_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_IMAGE_ATTACHMENT_COUNT = 4;
    private static final int MAX_VIDEO_ATTACHMENT_COUNT = 1;
    private static final long MAX_IMAGE_ATTACHMENT_SIZE_BYTES = 20L * 1024L * 1024L;
    private static final long MAX_VIDEO_ATTACHMENT_SIZE_BYTES = 100L * 1024L * 1024L;
    private static final double MAX_VIDEO_ATTACHMENT_DURATION_SECONDS = 120.0;

    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final MessageConversationRepository messageConversationRepository;
    private final MessageParticipantRepository messageParticipantRepository;
    private final DirectMessageRepository directMessageRepository;
    private final DirectMessageAttachmentRepository directMessageAttachmentRepository;
    private final MessageAttachmentStorageService messageAttachmentStorageService;
    private final MessageResponseAssembler messageResponseAssembler;
    private final MessageRealtimeService messageRealtimeService;
    private final ContentModerationService contentModerationService;
    private final MediaUploadSecurityService mediaUploadSecurityService;
    private final LightweightSecurityScanService lightweightSecurityScanService;
    private final TextSecurityService textSecurityService;
    private final VideoMetadataService videoMetadataService;
    private final VideoOptimizationService videoOptimizationService;
    private final SseStreamTokenService sseStreamTokenService;

    public MessageService(
            UserRepository userRepository,
            PostRepository postRepository,
            MessageConversationRepository messageConversationRepository,
            MessageParticipantRepository messageParticipantRepository,
            DirectMessageRepository directMessageRepository,
            DirectMessageAttachmentRepository directMessageAttachmentRepository,
            MessageAttachmentStorageService messageAttachmentStorageService,
            MessageResponseAssembler messageResponseAssembler,
            MessageRealtimeService messageRealtimeService,
            ContentModerationService contentModerationService,
            MediaUploadSecurityService mediaUploadSecurityService,
            LightweightSecurityScanService lightweightSecurityScanService,
            TextSecurityService textSecurityService,
            VideoMetadataService videoMetadataService,
            VideoOptimizationService videoOptimizationService,
            SseStreamTokenService sseStreamTokenService
    ) {
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.messageConversationRepository = messageConversationRepository;
        this.messageParticipantRepository = messageParticipantRepository;
        this.directMessageRepository = directMessageRepository;
        this.directMessageAttachmentRepository = directMessageAttachmentRepository;
        this.messageAttachmentStorageService = messageAttachmentStorageService;
        this.messageResponseAssembler = messageResponseAssembler;
        this.messageRealtimeService = messageRealtimeService;
        this.contentModerationService = contentModerationService;
        this.mediaUploadSecurityService = mediaUploadSecurityService;
        this.lightweightSecurityScanService = lightweightSecurityScanService;
        this.textSecurityService = textSecurityService;
        this.videoMetadataService = videoMetadataService;
        this.videoOptimizationService = videoOptimizationService;
        this.sseStreamTokenService = sseStreamTokenService;
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> getConversations(CustomUserPrincipal principal) {
        User viewer = getCurrentUser(principal);
        List<MessageParticipant> viewerParticipants =
                messageParticipantRepository.findActiveByUserIdWithConversation(viewer.getId());

        return viewerParticipants.stream()
                .map(participant -> toConversationResponse(participant.getConversation(), viewer.getId(), participant))
                .toList();
    }

    public ConversationResponse createConversation(
            CustomUserPrincipal principal,
            CreateConversationRequest request
    ) {
        User viewer = getCurrentUser(principal);
        User recipient = getRecipientUser(request.recipientId(), request.recipientHandle(), viewer.getId());

        MessageConversation conversation = getOrCreateDirectConversation(viewer, recipient);
        MessageParticipant viewerParticipant = getOrCreateParticipant(conversation, viewer);
        return toConversationResponse(conversation, viewer.getId(), viewerParticipant);
    }

    public SseEmitter streamMessages(CustomUserPrincipal principal) {
        User viewer = getCurrentUser(principal);
        return messageRealtimeService.subscribe(viewer.getId());
    }

    @Transactional(readOnly = true)
    public SseStreamTokenService.IssuedToken issueStreamToken(CustomUserPrincipal principal) {
        User viewer = getCurrentUser(principal);
        return sseStreamTokenService.issue(viewer.getId());
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<MessageResponse> getMessages(
            CustomUserPrincipal principal,
            UUID conversationId,
            String cursor,
            int size
    ) {
        User viewer = getCurrentUser(principal);
        MessageParticipant viewerParticipant = getParticipant(conversationId, viewer.getId());

        int pageSize = clampSize(size, DEFAULT_MESSAGE_PAGE_SIZE);
        MessageCursor messageCursor = parseMessageCursor(cursor);
        List<DirectMessage> loadedMessages = loadMessagePage(
                conversationId,
                messageCursor,
                viewerParticipant.getClearedAt(),
                PageRequest.of(0, pageSize + 1)
        );

        boolean hasNext = loadedMessages.size() > pageSize;
        List<DirectMessage> pageMessagesDesc = hasNext ? loadedMessages.subList(0, pageSize) : loadedMessages;
        List<DirectMessage> pageMessages = new ArrayList<>(pageMessagesDesc);
        Collections.reverse(pageMessages);

        List<MessageParticipant> participants = messageParticipantRepository.findByConversationId(conversationId);
        Map<UUID, List<DirectMessageAttachment>> attachmentMap = buildAttachmentMap(pageMessages);

        List<MessageResponse> items = pageMessages.stream()
                .map(message -> messageResponseAssembler.toMessage(
                        message,
                        viewer.getId(),
                        attachmentMap.getOrDefault(message.getId(), List.of()),
                        participants
                ))
                .toList();

        return new CursorPageResponse<>(items, buildMessageCursor(pageMessages, hasNext), hasNext);
    }

    public MessageResponse sendMessage(
            CustomUserPrincipal principal,
            UUID conversationId,
            SendMessageRequest request
    ) {
        User viewer = getCurrentUser(principal);
        MessageConversation conversation = getActiveConversation(conversationId);
        MessageParticipant viewerParticipant = getParticipant(conversationId, viewer.getId());

        String content = normalizeContent(request.content());
        Post sharedPost = getSharedPost(request.sharedPostId());
        if (content == null && sharedPost == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message content or shared post is required.");
        }
        validateMessageContent(content);

        DirectMessage savedMessage = saveMessage(
                conversation,
                viewer,
                content != null ? content : "",
                sharedPost
        );
        viewerParticipant.markRead();
        publishMessageCreated(savedMessage);

        return toMessageResponse(savedMessage, viewer.getId());
    }

    public MessageResponse sendMultipartMessage(
            CustomUserPrincipal principal,
            UUID conversationId,
            SendMultipartMessageRequest request
    ) {
        User viewer = getCurrentUser(principal);
        MessageConversation conversation = getActiveConversation(conversationId);
        MessageParticipant viewerParticipant = getParticipant(conversationId, viewer.getId());

        String content = normalizeContent(request.getContent());
        Post sharedPost = getSharedPost(request.getSharedPostId());
        List<MultipartFile> attachments = normalizeAttachmentFiles(request.getAttachments());
        validateAttachments(attachments);

        if (content == null && sharedPost == null && attachments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message content, shared post, or attachment is required.");
        }
        validateMultipartMessageContent(content, attachments);
        List<PreparedMessageAttachment> preparedAttachments = prepareAttachments(attachments);

        List<MessageAttachmentStorageService.StoredFile> storedFiles = new ArrayList<>();
        try {
            DirectMessage savedMessage = saveMessage(
                    conversation,
                    viewer,
                    content != null ? content : "",
                    sharedPost
            );

            List<DirectMessageAttachment> messageAttachments = new ArrayList<>();
            for (int index = 0; index < preparedAttachments.size(); index++) {
                PreparedMessageAttachment preparedAttachment = preparedAttachments.get(index);
                MessageAttachmentStorageService.StoredFile storedFile = storePreparedAttachment(preparedAttachment);
                storedFiles.add(storedFile);
                messageAttachments.add(DirectMessageAttachment.create(
                        savedMessage,
                        preparedAttachment.type(),
                        preparedAttachment.displayName(),
                        storedFile.publicUrl(),
                        index
                ));
            }

            directMessageAttachmentRepository.saveAll(messageAttachments);
            viewerParticipant.markRead();
            publishMessageCreated(savedMessage);
            return toMessageResponse(savedMessage, viewer.getId());
        } catch (Exception ex) {
            storedFiles.forEach(messageAttachmentStorageService::deleteQuietly);
            if (ex instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upload message attachments.", ex);
        } finally {
            preparedAttachments.forEach(this::deleteTemporaryPreparedAttachment);
        }
    }

    public void deleteMessage(
            CustomUserPrincipal principal,
            UUID conversationId,
            UUID messageId
    ) {
        User viewer = getCurrentUser(principal);
        getParticipant(conversationId, viewer.getId());

        DirectMessage message = getActiveMessage(conversationId, messageId);
        if (!message.isSentBy(viewer.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the sender can delete this message.");
        }

        deleteMessageAttachmentFilesAfterCommit(message);
        message.softDelete();
        publishMessageDeleted(message);
    }

    @Transactional(readOnly = true)
    public MessageAttachmentFile getMessageAttachmentFile(CustomUserPrincipal principal, UUID attachmentId) {
        User viewer = getCurrentUser(principal);
        DirectMessageAttachment attachment = directMessageAttachmentRepository
                .findAccessibleActiveById(attachmentId, viewer.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message attachment not found."));

        Path attachmentPath = messageAttachmentStorageService.resolvePublicUrl(attachment.getFileUrl())
                .filter(Files::isRegularFile)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message attachment file not found."));
        return new MessageAttachmentFile(
                attachmentPath,
                attachment.getFileName(),
                resolveAttachmentContentType(attachment)
        );
    }

    public void leaveConversation(CustomUserPrincipal principal, UUID conversationId) {
        User viewer = getCurrentUser(principal);
        MessageParticipant participant = getParticipant(conversationId, viewer.getId());
        participant.softDelete();
    }

    public void markRead(CustomUserPrincipal principal, UUID conversationId) {
        User viewer = getCurrentUser(principal);
        MessageParticipant participant = getParticipant(conversationId, viewer.getId());
        participant.markRead();
    }

    @Transactional(readOnly = true)
    public List<MessageRecipientResponse> searchRecipients(
            CustomUserPrincipal principal,
            String keyword,
            int size
    ) {
        User viewer = getCurrentUser(principal);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        int pageSize = clampSize(size, DEFAULT_RECIPIENT_SEARCH_SIZE);

        return userRepository.searchMessageRecipients(
                        viewer.getId(),
                        normalizedKeyword,
                        PageRequest.of(0, pageSize)
                )
                .stream()
                .filter(User::isActive)
                .map(messageResponseAssembler::toRecipient)
                .toList();
    }

    public List<ConversationResponse> sharePost(
            CustomUserPrincipal principal,
            SharePostMessageRequest request
    ) {
        User viewer = getCurrentUser(principal);
        Post sharedPost = getRequiredSharedPost(request.postId());
        String content = normalizeContent(request.content());
        List<User> recipients = getShareRecipients(request, viewer.getId());

        validateMessageContent(content);

        List<ConversationResponse> responses = new ArrayList<>();
        for (User recipient : recipients) {
            MessageConversation conversation = getOrCreateDirectConversation(viewer, recipient);
            MessageParticipant viewerParticipant = getOrCreateParticipant(conversation, viewer);
            DirectMessage savedMessage = saveMessage(conversation, viewer, content != null ? content : "", sharedPost);
            viewerParticipant.markRead();
            publishMessageCreated(savedMessage);
            responses.add(toConversationResponse(conversation, viewer.getId(), viewerParticipant));
        }
        return responses;
    }

    private MessageConversation getOrCreateDirectConversation(User viewer, User recipient) {
        if (viewer.getId().equals(recipient.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot create a conversation with yourself.");
        }

        String directKey = buildDirectKey(viewer.getId(), recipient.getId());
        messageConversationRepository.insertDirectConversationIfAbsent(directKey);
        MessageConversation conversation = messageConversationRepository.findByDirectKeyAndDeletedAtIsNull(directKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Direct conversation is not available."));
        messageParticipantRepository.insertParticipantIfAbsent(conversation.getId(), viewer.getId());
        messageParticipantRepository.insertParticipantIfAbsent(conversation.getId(), recipient.getId());
        return conversation;
    }

    private MessageParticipant getOrCreateParticipant(MessageConversation conversation, User user) {
        return messageParticipantRepository.findByConversationIdAndUserId(conversation.getId(), user.getId())
                .map(participant -> {
                    if (participant.getDeletedAt() != null) {
                        participant.reactivate();
                    }
                    return participant;
                })
                .orElseGet(() -> messageParticipantRepository.save(MessageParticipant.create(conversation, user)));
    }

    private DirectMessage saveMessage(
            MessageConversation conversation,
            User sender,
            String content,
            Post sharedPost
    ) {
        reactivateRecipientsForIncomingMessage(conversation, sender.getId());
        DirectMessage savedMessage = directMessageRepository.save(DirectMessage.create(
                conversation,
                sender,
                content,
                sharedPost
        ));
        conversation.updateLastMessage(savedMessage.getId());
        return savedMessage;
    }

    private void reactivateRecipientsForIncomingMessage(MessageConversation conversation, UUID senderId) {
        List<MessageParticipant> participants = messageParticipantRepository.findByConversationId(conversation.getId());
        for (MessageParticipant participant : participants) {
            if (!participant.getUser().getId().equals(senderId) && participant.getDeletedAt() != null) {
                participant.reactivateForIncomingMessage();
            }
        }
    }

    private void deleteMessageAttachmentFilesAfterCommit(DirectMessage message) {
        List<DirectMessageAttachment> attachments = directMessageAttachmentRepository.findByMessageIds(List.of(message.getId()));
        runAfterCommit(() -> {
            for (DirectMessageAttachment attachment : attachments) {
                messageAttachmentStorageService.deletePublicUrlQuietly(attachment.getFileUrl());
            }
        });
    }

    private ConversationResponse toConversationResponse(
            MessageConversation conversation,
            UUID viewerId,
            MessageParticipant viewerParticipant
    ) {
        List<MessageParticipant> participants =
                messageParticipantRepository.findByConversationId(conversation.getId());
        User recipient = participants.stream()
                .map(MessageParticipant::getUser)
                .filter(user -> !user.getId().equals(viewerId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation recipient not found."));

        List<DirectMessage> recentMessages = new ArrayList<>(loadRecentMessages(
                conversation.getId(),
                viewerParticipant.getClearedAt(),
                PageRequest.of(0, DEFAULT_MESSAGE_PAGE_SIZE)
        ));
        Collections.reverse(recentMessages);

        return messageResponseAssembler.toConversation(
                conversation,
                recipient,
                recentMessages,
                buildAttachmentMap(recentMessages),
                participants,
                viewerId,
                countUnreadMessages(
                        conversation.getId(),
                        viewerId,
                        viewerParticipant.getLastReadAt(),
                        viewerParticipant.getClearedAt()
                )
        );
    }

    private long countUnreadMessages(
            UUID conversationId,
            UUID viewerId,
            OffsetDateTime lastReadAt,
            OffsetDateTime clearedAt
    ) {
        OffsetDateTime threshold = latest(lastReadAt, clearedAt);
        if (threshold == null) {
            return directMessageRepository.countUnreadMessagesWithoutReadAt(conversationId, viewerId);
        }
        return directMessageRepository.countUnreadMessages(conversationId, viewerId, threshold);
    }

    private List<DirectMessage> loadRecentMessages(
            UUID conversationId,
            OffsetDateTime clearedAt,
            PageRequest pageRequest
    ) {
        if (clearedAt == null) {
            return directMessageRepository.findRecentActiveByConversationId(conversationId, pageRequest);
        }
        return directMessageRepository.findRecentActiveByConversationIdAfter(conversationId, clearedAt, pageRequest);
    }

    private List<DirectMessage> loadMessagePage(
            UUID conversationId,
            MessageCursor cursor,
            OffsetDateTime clearedAt,
            PageRequest pageRequest
    ) {
        if (cursor == null && clearedAt == null) {
            return directMessageRepository.findActivePageByConversationId(conversationId, pageRequest);
        }
        if (cursor == null) {
            return directMessageRepository.findActivePageByConversationIdAfter(conversationId, clearedAt, pageRequest);
        }
        if (clearedAt == null) {
            return directMessageRepository.findActivePageByConversationIdBefore(
                    conversationId,
                    cursor.createdAt(),
                    cursor.id(),
                    pageRequest
            );
        }
        return directMessageRepository.findActivePageByConversationIdBeforeAndAfter(
                conversationId,
                cursor.createdAt(),
                cursor.id(),
                clearedAt,
                pageRequest
        );
    }

    private OffsetDateTime latest(OffsetDateTime left, OffsetDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    private MessageResponse toMessageResponse(DirectMessage message, UUID viewerId) {
        List<MessageParticipant> participants =
                messageParticipantRepository.findByConversationId(message.getConversation().getId());
        Map<UUID, List<DirectMessageAttachment>> attachmentMap = buildAttachmentMap(List.of(message));
        return messageResponseAssembler.toMessage(
                message,
                viewerId,
                attachmentMap.getOrDefault(message.getId(), List.of()),
                participants
        );
    }

    private void publishMessageCreated(DirectMessage message) {
        publishMessageEvent(message);
    }

    private void publishMessageDeleted(DirectMessage message) {
        List<MessageParticipant> participants =
                messageParticipantRepository.findByConversationIdAndDeletedAtIsNull(message.getConversation().getId());
        for (MessageParticipant participant : participants) {
            publishAfterCommit(
                    participant.getUser().getId(),
                    MessageRealtimeEvent.deleted(message.getConversation().getId(), message.getId())
            );
        }
    }

    private void publishMessageEvent(DirectMessage message) {
        List<MessageParticipant> participants =
                messageParticipantRepository.findByConversationIdAndDeletedAtIsNull(message.getConversation().getId());
        Map<UUID, List<DirectMessageAttachment>> attachmentMap = buildAttachmentMap(List.of(message));
        for (MessageParticipant participant : participants) {
            UUID viewerId = participant.getUser().getId();
            MessageResponse response = messageResponseAssembler.toMessage(
                    message,
                    viewerId,
                    attachmentMap.getOrDefault(message.getId(), List.of()),
                    participants
            );
            publishAfterCommit(
                    viewerId,
                    MessageRealtimeEvent.created(message.getConversation().getId(), response)
            );
        }
    }

    private void publishAfterCommit(UUID userId, MessageRealtimeEvent event) {
        runAfterCommit(() -> messageRealtimeService.publish(userId, event));
    }

    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private Map<UUID, List<DirectMessageAttachment>> buildAttachmentMap(List<DirectMessage> messages) {
        if (messages.isEmpty()) {
            return Map.of();
        }

        List<UUID> messageIds = messages.stream()
                .map(DirectMessage::getId)
                .toList();
        Map<UUID, List<DirectMessageAttachment>> attachmentMap = new LinkedHashMap<>();
        for (DirectMessageAttachment attachment : directMessageAttachmentRepository.findByMessageIds(messageIds)) {
            attachmentMap.computeIfAbsent(attachment.getMessage().getId(), ignored -> new ArrayList<>())
                    .add(attachment);
        }
        return attachmentMap;
    }

    private MessageParticipant getParticipant(UUID conversationId, UUID userId) {
        return messageParticipantRepository.findByConversationIdAndUserIdAndDeletedAtIsNull(conversationId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Conversation access is denied."));
    }

    private void ensureParticipant(UUID conversationId, UUID userId) {
        if (!messageParticipantRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(conversationId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Conversation access is denied.");
        }
    }

    private MessageConversation getActiveConversation(UUID conversationId) {
        return messageConversationRepository.findByIdAndDeletedAtIsNull(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found."));
    }

    private DirectMessage getActiveMessage(UUID conversationId, UUID messageId) {
        return directMessageRepository.findByIdAndConversationIdAndDeletedAtIsNull(messageId, conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found."));
    }

    private User getCurrentUser(CustomUserPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }

        User user = userRepository.findByIdAndDeletedAtIsNull(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found."));
        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not active.");
        }
        return user;
    }

    private User getRecipientUser(UUID recipientId, String recipientHandle, UUID viewerId) {
        User recipient;
        if (recipientId != null) {
            recipient = userRepository.findByIdAndDeletedAtIsNull(recipientId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipient not found."));
        } else {
            String normalizedHandle = normalizeHandle(recipientHandle);
            if (normalizedHandle == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recipient is required.");
            }
            recipient = userRepository.findByHandleAndDeletedAtIsNull(normalizedHandle)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipient not found."));
        }

        if (recipient.getId().equals(viewerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot send a message to yourself.");
        }
        if (!recipient.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recipient is not active.");
        }
        return recipient;
    }

    private List<User> getShareRecipients(SharePostMessageRequest request, UUID viewerId) {
        Map<UUID, User> recipients = new LinkedHashMap<>();

        if (request.recipientIds() != null) {
            for (UUID recipientId : request.recipientIds()) {
                User recipient = getRecipientUser(recipientId, null, viewerId);
                recipients.put(recipient.getId(), recipient);
            }
        }

        if (request.recipientHandles() != null) {
            for (String recipientHandle : request.recipientHandles()) {
                User recipient = getRecipientUser(null, recipientHandle, viewerId);
                recipients.put(recipient.getId(), recipient);
            }
        }

        if (recipients.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one recipient is required.");
        }
        return new ArrayList<>(recipients.values());
    }

    private Post getSharedPost(UUID sharedPostId) {
        if (sharedPostId == null) {
            return null;
        }
        return getRequiredSharedPost(sharedPostId);
    }

    private Post getRequiredSharedPost(UUID sharedPostId) {
        return postRepository.findByIdAndDeletedAtIsNull(sharedPostId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shared post not found."));
    }

    private String buildDirectKey(UUID left, UUID right) {
        List<String> ids = new ArrayList<>(List.of(left.toString(), right.toString()));
        Collections.sort(ids);
        return ids.get(0) + ":" + ids.get(1);
    }

    private int clampSize(int requested, int fallback) {
        if (requested <= 0) {
            return fallback;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private MessageCursor parseMessageCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }

        String[] values = cursor.split("\\|");
        if (values.length != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid message cursor.");
        }
        try {
            return new MessageCursor(OffsetDateTime.parse(values[0]), UUID.fromString(values[1]));
        } catch (DateTimeParseException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid message cursor.", ex);
        }
    }

    private String buildMessageCursor(List<DirectMessage> messages, boolean hasNext) {
        if (!hasNext || messages.isEmpty()) {
            return null;
        }

        DirectMessage oldest = messages.get(0);
        return oldest.getCreatedAt() + "|" + oldest.getId();
    }

    private record MessageCursor(OffsetDateTime createdAt, UUID id) {
    }

    private String normalizeHandle(String handle) {
        if (handle == null) {
            return null;
        }
        String normalized = handle.trim();
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeContent(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<MultipartFile> normalizeAttachmentFiles(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        return files.stream()
                .filter(file -> file != null && !file.isEmpty())
                .toList();
    }

    private void validateAttachments(List<MultipartFile> attachments) {
        if (attachments.isEmpty()) {
            return;
        }

        long imageCount = 0L;
        long videoCount = 0L;
        for (MultipartFile attachment : attachments) {
            MessageAttachmentType type = resolveAttachmentType(attachment);
            if (type == MessageAttachmentType.IMAGE) {
                imageCount++;
                if (attachment.getSize() > MAX_IMAGE_ATTACHMENT_SIZE_BYTES) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image attachment must be 20MB or smaller.");
                }
                mediaUploadSecurityService.assertImageFileSafe(attachment);
                lightweightSecurityScanService.assertFileClean(attachment);
            } else {
                videoCount++;
                if (attachment.getSize() > MAX_VIDEO_ATTACHMENT_SIZE_BYTES) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video attachment must be 100MB or smaller.");
                }
                validateVideoAttachment(attachment);
            }
        }

        if (imageCount > 0 && videoCount > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image and video attachments cannot be mixed.");
        }
        if (imageCount > MAX_IMAGE_ATTACHMENT_COUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can attach up to 4 images.");
        }
        if (videoCount > MAX_VIDEO_ATTACHMENT_COUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can attach only one video.");
        }
    }

    private void validateVideoAttachment(MultipartFile videoFile) {
        mediaUploadSecurityService.assertVideoFileSafe(videoFile);
        lightweightSecurityScanService.assertFileClean(videoFile);

        double videoLengthSeconds = videoMetadataService.readDurationSeconds(videoFile);
        if (videoLengthSeconds > MAX_VIDEO_ATTACHMENT_DURATION_SECONDS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video attachment duration must be 2 minutes or shorter.");
        }
    }

    private void validateMessageContent(String content) {
        textSecurityService.assertTextSafe(content);
        contentModerationService.assertTextAllowed(content);
    }

    private void validateMultipartMessageContent(String content, List<MultipartFile> attachments) {
        textSecurityService.assertTextSafe(content);
        contentModerationService.assertMessageAllowed(content, attachments);
    }

    private List<PreparedMessageAttachment> prepareAttachments(List<MultipartFile> attachments) {
        if (attachments.isEmpty()) {
            return List.of();
        }

        return attachments.stream()
                .map(this::prepareAttachment)
                .toList();
    }

    private PreparedMessageAttachment prepareAttachment(MultipartFile attachment) {
        MessageAttachmentType type = resolveAttachmentType(attachment);
        String displayName = attachment.getOriginalFilename() != null ? attachment.getOriginalFilename() : "attachment";
        if (type == MessageAttachmentType.IMAGE) {
            return PreparedMessageAttachment.image(
                    displayName,
                    mediaUploadSecurityService.prepareImage(attachment)
            );
        }
        return PreparedMessageAttachment.video(
                displayName,
                videoOptimizationService.prepareVideo(attachment)
        );
    }

    private MessageAttachmentStorageService.StoredFile storePreparedAttachment(
            PreparedMessageAttachment preparedAttachment
    ) throws IOException {
        if (preparedAttachment.type() == MessageAttachmentType.IMAGE) {
            return messageAttachmentStorageService.store(preparedAttachment.imageFile());
        }
        return messageAttachmentStorageService.store(preparedAttachment.videoFile());
    }

    private void deleteTemporaryPreparedAttachment(PreparedMessageAttachment preparedAttachment) {
        if (preparedAttachment.type() == MessageAttachmentType.VIDEO) {
            messageAttachmentStorageService.deleteQuietly(preparedAttachment.videoFile());
        }
    }

    private String resolveAttachmentContentType(DirectMessageAttachment attachment) {
        if (attachment.getAttachmentType() == MessageAttachmentType.IMAGE) {
            return "image/jpeg";
        }

        String fileUrl = attachment.getFileUrl() != null ? attachment.getFileUrl().toLowerCase(Locale.ROOT) : "";
        if (fileUrl.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (fileUrl.endsWith(".m4v")) {
            return "video/x-m4v";
        }
        if (fileUrl.endsWith(".webm")) {
            return "video/webm";
        }
        return "video/mp4";
    }

    private MessageAttachmentType resolveAttachmentType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null) {
            String normalized = contentType.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("image/")) {
                return MessageAttachmentType.IMAGE;
            }
            if (normalized.startsWith("video/")) {
                return MessageAttachmentType.VIDEO;
            }
        }

        String fileName = file.getOriginalFilename();
        if (fileName != null) {
            String normalized = fileName.toLowerCase(Locale.ROOT);
            if (normalized.endsWith(".jpg") || normalized.endsWith(".jpeg") || normalized.endsWith(".png")
                    || normalized.endsWith(".gif") || normalized.endsWith(".webp")) {
                return MessageAttachmentType.IMAGE;
            }
            if (normalized.endsWith(".mp4") || normalized.endsWith(".mov") || normalized.endsWith(".webm")
                    || normalized.endsWith(".m4v")) {
                return MessageAttachmentType.VIDEO;
            }
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported message attachment file type.");
    }

    private record PreparedMessageAttachment(
            MessageAttachmentType type,
            String displayName,
            MediaStorageService.PreparedMediaFile imageFile,
            MediaStorageService.PreparedMediaPath videoFile
    ) {

        private static PreparedMessageAttachment image(
                String displayName,
                MediaStorageService.PreparedMediaFile imageFile
        ) {
            return new PreparedMessageAttachment(MessageAttachmentType.IMAGE, displayName, imageFile, null);
        }

        private static PreparedMessageAttachment video(
                String displayName,
                MediaStorageService.PreparedMediaPath videoFile
        ) {
            return new PreparedMessageAttachment(MessageAttachmentType.VIDEO, displayName, null, videoFile);
        }
    }

    public record MessageAttachmentFile(Path path, String fileName, String contentType) {
    }
}
