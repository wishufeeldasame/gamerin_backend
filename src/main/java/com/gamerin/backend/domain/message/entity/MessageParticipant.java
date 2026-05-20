package com.gamerin.backend.domain.message.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "message_participants")
public class MessageParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id")
    private MessageConversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "last_read_at")
    private OffsetDateTime lastReadAt;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private OffsetDateTime joinedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected MessageParticipant() {
    }

    public static MessageParticipant create(MessageConversation conversation, User user) {
        MessageParticipant participant = new MessageParticipant();
        participant.conversation = conversation;
        participant.user = user;
        return participant;
    }

    @PrePersist
    protected void onCreate() {
        this.joinedAt = OffsetDateTime.now();
    }

    public void markRead() {
        this.lastReadAt = OffsetDateTime.now();
    }

    public void softDelete() {
        this.deletedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public MessageConversation getConversation() {
        return conversation;
    }

    public User getUser() {
        return user;
    }

    public OffsetDateTime getLastReadAt() {
        return lastReadAt;
    }

    public OffsetDateTime getJoinedAt() {
        return joinedAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }
}
