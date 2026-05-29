package com.gamerin.backend.domain.message.service;

import java.time.OffsetDateTime;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.message.dto.request.CreateConversationRequest;
import com.gamerin.backend.domain.message.dto.request.SendMessageRequest;
import com.gamerin.backend.domain.message.dto.request.SendMultipartMessageRequest;
import com.gamerin.backend.domain.message.dto.request.SharePostMessageRequest;
import com.gamerin.backend.domain.message.dto.request.UpdateMessageRequest;
import com.gamerin.backend.domain.message.dto.response.ConversationResponse;
import com.gamerin.backend.domain.message.dto.response.MessageRecipientResponse;
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
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

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

    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final MessageConversationRepository messageConversationRepository;
    private final MessageParticipantRepository messageParticipantRepository;
    private final DirectMessageRepository directMessageRepository;
    private final DirectMessageAttachmentRepository directMessageAttachmentRepository;
    private final MessageAttachmentStorageService messageAttachmentStorageService;
    private final MessageResponseAssembler messageResponseAssembler;

    public MessageService(
            UserRepository userRepository,
            PostRepository postRepository,
            MessageConversationRepository messageConversationRepository,
            MessageParticipantRepository messageParticipantRepository,
            DirectMessageRepository directMessageRepository,
            DirectMessageAttachmentRepository directMessageAttachmentRepository,
            MessageAttachmentStorageService messageAttachmentStorageService,
            MessageResponseAssembler messageResponseAssembler
    ) {
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.messageConversationRepository = messageConversationRepository;
        this.messageParticipantRepository = messageParticipantRepository;
        this.directMessageRepository = directMessageRepository;
        this.directMessageAttachmentRepository = directMessageAttachmentRepository;
        this.messageAttachmentStorageService = messageAttachmentStorageService;
        this.messageResponseAssembler = messageResponseAssembler;
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

    @Transactional(readOnly = true)
    public CursorPageResponse<MessageResponse> getMessages(
            CustomUserPrincipal principal,
            UUID conversationId,
            String cursor,
            int size
    ) {
        User viewer = getCurrentUser(principal);
        ensureParticipant(conversationId, viewer.getId());

        int pageSize = clampSize(size, DEFAULT_MESSAGE_PAGE_SIZE);
        OffsetDateTime cursorCreatedAt = parseMessageCursor(cursor);
        List<DirectMessage> loadedMessages = cursorCreatedAt == null
                ? directMessageRepository.findActivePageByConversationId(
                        conversationId,
                        PageRequest.of(0, pageSize + 1)
                )
                : directMessageRepository.findActivePageByConversationIdBefore(
                        conversationId,
                        cursorCreatedAt,
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

        DirectMessage savedMessage = saveMessage(conversation, viewer, content, sharedPost);
        viewerParticipant.markRead();

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

        DirectMessage savedMessage = saveMessage(
                conversation,
                viewer,
                content != null ? content : "",
                sharedPost
        );

        List<MessageAttachmentStorageService.StoredFile> storedFiles = new ArrayList<>();
        try {
            List<DirectMessageAttachment> messageAttachments = new ArrayList<>();
            for (int index = 0; index < attachments.size(); index++) {
                MultipartFile attachmentFile = attachments.get(index);
                MessageAttachmentStorageService.StoredFile storedFile = messageAttachmentStorageService.store(attachmentFile);
                storedFiles.add(storedFile);
                messageAttachments.add(DirectMessageAttachment.create(
                        savedMessage,
                        resolveAttachmentType(attachmentFile),
                        attachmentFile.getOriginalFilename() != null ? attachmentFile.getOriginalFilename() : "attachment",
                        storedFile.publicUrl(),
                        index
                ));
            }

            directMessageAttachmentRepository.saveAll(messageAttachments);
            viewerParticipant.markRead();
            return toMessageResponse(savedMessage, viewer.getId());
        } catch (Exception ex) {
            storedFiles.forEach(messageAttachmentStorageService::deleteQuietly);
            if (ex instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upload message attachments.", ex);
        }
    }

    public MessageResponse updateMessage(
            CustomUserPrincipal principal,
            UUID conversationId,
            UUID messageId,
            UpdateMessageRequest request
    ) {
        User viewer = getCurrentUser(principal);
        getParticipant(conversationId, viewer.getId());

        DirectMessage message = getActiveMessage(conversationId, messageId);
        if (!message.isSentBy(viewer.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the sender can modify this message.");
        }

        String content = normalizeContent(request.content());
        if (content == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message content is required.");
        }

        message.edit(content);
        return toMessageResponse(message, viewer.getId());
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

        message.softDelete();
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

        List<ConversationResponse> responses = new ArrayList<>();
        for (User recipient : recipients) {
            MessageConversation conversation = getOrCreateDirectConversation(viewer, recipient);
            MessageParticipant viewerParticipant = getOrCreateParticipant(conversation, viewer);
            DirectMessage savedMessage = saveMessage(conversation, viewer, content != null ? content : "", sharedPost);
            viewerParticipant.markRead();
            responses.add(toConversationResponse(conversation, viewer.getId(), viewerParticipant));
        }
        return responses;
    }

    private MessageConversation getOrCreateDirectConversation(User viewer, User recipient) {
        if (viewer.getId().equals(recipient.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot create a conversation with yourself.");
        }

        String directKey = buildDirectKey(viewer.getId(), recipient.getId());
        return messageConversationRepository.findByDirectKeyAndDeletedAtIsNull(directKey)
                .map(conversation -> {
                    getOrCreateParticipant(conversation, viewer);
                    getOrCreateParticipant(conversation, recipient);
                    return conversation;
                })
                .orElseGet(() -> {
                    MessageConversation conversation =
                            messageConversationRepository.save(MessageConversation.createDirect(directKey));
                    messageParticipantRepository.save(MessageParticipant.create(conversation, viewer));
                    messageParticipantRepository.save(MessageParticipant.create(conversation, recipient));
                    return conversation;
                });
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
        DirectMessage savedMessage = directMessageRepository.save(DirectMessage.create(
                conversation,
                sender,
                content,
                sharedPost
        ));
        conversation.updateLastMessage(savedMessage.getId());
        return savedMessage;
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

        List<DirectMessage> recentMessages = new ArrayList<>(directMessageRepository.findRecentActiveByConversationId(
                conversation.getId(),
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
                countUnreadMessages(conversation.getId(), viewerId, viewerParticipant.getLastReadAt())
        );
    }

    private long countUnreadMessages(UUID conversationId, UUID viewerId, OffsetDateTime lastReadAt) {
        if (lastReadAt == null) {
            return directMessageRepository.countUnreadMessagesWithoutReadAt(conversationId, viewerId);
        }
        return directMessageRepository.countUnreadMessages(conversationId, viewerId, lastReadAt);
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

    private OffsetDateTime parseMessageCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }

        String[] values = cursor.split("\\|");
        if (values.length == 0) {
            return null;
        }
        return OffsetDateTime.parse(values[0]);
    }

    private String buildMessageCursor(List<DirectMessage> messages, boolean hasNext) {
        if (!hasNext || messages.isEmpty()) {
            return null;
        }

        DirectMessage oldest = messages.get(0);
        return oldest.getCreatedAt() + "|" + oldest.getId();
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
            } else {
                videoCount++;
                if (attachment.getSize() > MAX_VIDEO_ATTACHMENT_SIZE_BYTES) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video attachment must be 100MB or smaller.");
                }
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
}
