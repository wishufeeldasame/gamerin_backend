package com.gamerin.backend.domain.message.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.gamerin.backend.domain.message.dto.response.ConversationResponse;
import com.gamerin.backend.domain.message.dto.response.MessageAttachmentResponse;
import com.gamerin.backend.domain.message.dto.response.MessageRecipientResponse;
import com.gamerin.backend.domain.message.dto.response.MessageResponse;
import com.gamerin.backend.domain.message.dto.response.SharedPostPreviewResponse;
import com.gamerin.backend.domain.message.entity.DirectMessage;
import com.gamerin.backend.domain.message.entity.DirectMessageAttachment;
import com.gamerin.backend.domain.message.entity.MessageConversation;
import com.gamerin.backend.domain.message.entity.MessageParticipant;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.user.entity.User;

@Component
public class MessageResponseAssembler {

    private static final int ONLINE_WINDOW_MINUTES = 5;

    public ConversationResponse toConversation(
            MessageConversation conversation,
            User recipient,
            List<DirectMessage> messages,
            Map<UUID, List<DirectMessageAttachment>> attachmentMap,
            List<MessageParticipant> participants,
            UUID viewerId,
            long unreadCount
    ) {
        return new ConversationResponse(
                conversation.getId(),
                toRecipient(recipient),
                messages.stream()
                        .map(message -> toMessage(
                                message,
                                viewerId,
                                attachmentMap.getOrDefault(message.getId(), List.of()),
                                participants
                        ))
                        .toList(),
                unreadCount,
                conversation.getUpdatedAt()
        );
    }

    public MessageResponse toMessage(
            DirectMessage message,
            UUID viewerId,
            List<DirectMessageAttachment> attachments,
            List<MessageParticipant> participants
    ) {
        boolean mine = message.getSender().getId().equals(viewerId);
        return new MessageResponse(
                message.getId(),
                mine ? "me" : message.getSender().getId().toString(),
                message.getContent() != null ? message.getContent() : "",
                message.getCreatedAt(),
                isRead(message, viewerId, participants),
                "sent",
                attachments.stream()
                        .map(this::toAttachment)
                        .toList(),
                toSharedPost(message.getSharedPost())
        );
    }

    public MessageRecipientResponse toRecipient(User user) {
        return new MessageRecipientResponse(
                user.getId(),
                user.getNickname(),
                "@" + user.getHandle(),
                user.getRole().name(),
                isOnline(user)
        );
    }

    private MessageAttachmentResponse toAttachment(DirectMessageAttachment attachment) {
        return new MessageAttachmentResponse(
                attachment.getId(),
                attachment.getAttachmentType().name().toLowerCase(),
                attachment.getFileName(),
                attachment.getFileUrl()
        );
    }

    private SharedPostPreviewResponse toSharedPost(Post post) {
        if (post == null) {
            return null;
        }

        return new SharedPostPreviewResponse(
                post.getId(),
                post.getAuthor().getNickname(),
                post.getAuthor().getHandle(),
                post.getContent() != null ? post.getContent() : "Shared post",
                post.getCreatedAt()
        );
    }

    private boolean isRead(DirectMessage message, UUID viewerId, List<MessageParticipant> participants) {
        if (message.getSender().getId().equals(viewerId)) {
            return participants.stream()
                    .filter(participant -> !participant.getUser().getId().equals(viewerId))
                    .allMatch(participant -> hasRead(participant, message.getCreatedAt()));
        }

        return participants.stream()
                .filter(participant -> participant.getUser().getId().equals(viewerId))
                .findFirst()
                .map(participant -> hasRead(participant, message.getCreatedAt()))
                .orElse(false);
    }

    private boolean hasRead(MessageParticipant participant, OffsetDateTime messageCreatedAt) {
        return participant.getLastReadAt() != null
                && !participant.getLastReadAt().isBefore(messageCreatedAt);
    }

    private boolean isOnline(User user) {
        return user.getLastLoginAt() != null
                && user.getLastLoginAt().isAfter(OffsetDateTime.now().minusMinutes(ONLINE_WINDOW_MINUTES));
    }
}
