package com.gamerin.backend.domain.notification.entity;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.gamerin.backend.domain.follow.entity.Follow;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.entity.PostLike;
import com.gamerin.backend.domain.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "notifications",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_notifications_post_like", columnNames = "post_like_id"),
                @UniqueConstraint(name = "uq_notifications_comment", columnNames = "comment_id"),
                @UniqueConstraint(name = "uq_notifications_follow", columnNames = "follow_id")
        }
)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private Post post;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_like_id")
    private PostLike postLike;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comment_id")
    private PostComment comment;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "follow_id")
    private Follow follow;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Notification() {
    }

    public static Notification like(User recipient, User actor, Post post, PostLike postLike) {
        Notification notification = base(recipient, actor, NotificationType.LIKE);
        notification.post = post;
        notification.postLike = postLike;
        return notification;
    }

    public static Notification comment(User recipient, User actor, Post post, PostComment comment) {
        Notification notification = base(recipient, actor, NotificationType.COMMENT);
        notification.post = post;
        notification.comment = comment;
        return notification;
    }

    public static Notification follow(User recipient, User actor, Follow follow) {
        Notification notification = base(recipient, actor, NotificationType.FOLLOW);
        notification.follow = follow;
        return notification;
    }

    private static Notification base(User recipient, User actor, NotificationType type) {
        Notification notification = new Notification();
        notification.recipient = recipient;
        notification.actor = actor;
        notification.type = type;
        return notification;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    public void markRead(OffsetDateTime readAt) {
        if (this.readAt == null) {
            this.readAt = readAt.truncatedTo(ChronoUnit.MICROS);
        }
    }

    public UUID getId() {
        return id;
    }

    public User getRecipient() {
        return recipient;
    }

    public User getActor() {
        return actor;
    }

    public NotificationType getType() {
        return type;
    }

    public Post getPost() {
        return post;
    }

    public PostComment getComment() {
        return comment;
    }

    public OffsetDateTime getReadAt() {
        return readAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
