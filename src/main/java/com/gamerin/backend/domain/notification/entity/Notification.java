package com.gamerin.backend.domain.notification.entity;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.gamerin.backend.domain.follow.entity.Follow;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.MentoringReview;
import com.gamerin.backend.domain.message.entity.DirectMessage;
import com.gamerin.backend.domain.message.entity.MessageConversation;
import com.gamerin.backend.domain.mention.entity.UserMention;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.entity.PostLike;
import com.gamerin.backend.domain.repost.entity.PostRepost;
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
                @UniqueConstraint(name = "uq_notifications_follow", columnNames = "follow_id"),
                @UniqueConstraint(name = "uq_notifications_post_repost", columnNames = "post_repost_id"),
                @UniqueConstraint(
                        name = "uq_notifications_recipient_conversation",
                        columnNames = {"recipient_id", "conversation_id"}
                ),
                @UniqueConstraint(
                        name = "uq_notifications_recipient_mentoring_event",
                        columnNames = {"recipient_id", "type", "mentoring_application_id"}
                ),
                @UniqueConstraint(name = "uq_notifications_mentoring_review", columnNames = "mentoring_review_id"),
                @UniqueConstraint(name = "uq_notifications_mention", columnNames = "mention_id")
        }
)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
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

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_repost_id")
    private PostRepost postRepost;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    private MessageConversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    private DirectMessage message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mentoring_application_id")
    private MentoringApplication mentoringApplication;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mentoring_review_id")
    private MentoringReview mentoringReview;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mention_id")
    private UserMention mention;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "event_at", nullable = false)
    private OffsetDateTime eventAt;

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

    public static Notification repost(User recipient, User actor, Post post, PostRepost postRepost) {
        Notification notification = base(recipient, actor, NotificationType.REPOST);
        notification.post = post;
        notification.postRepost = postRepost;
        return notification;
    }

    public static Notification directMessage(
            User recipient,
            User actor,
            MessageConversation conversation,
            DirectMessage message
    ) {
        Notification notification = base(recipient, actor, NotificationType.DIRECT_MESSAGE);
        notification.conversation = conversation;
        notification.refreshDirectMessage(actor, message, true);
        return notification;
    }

    public static Notification mentoring(
            User recipient,
            User actor,
            NotificationType type,
            MentoringApplication application
    ) {
        Notification notification = base(recipient, actor, type);
        notification.mentoringApplication = application;
        return notification;
    }

    public static Notification mentoringReview(
            User recipient,
            User actor,
            MentoringApplication application,
            MentoringReview review
    ) {
        Notification notification = mentoring(
                recipient,
                actor,
                NotificationType.MENTORING_REVIEW,
                application
        );
        notification.mentoringReview = review;
        return notification;
    }

    public static Notification mention(
            User recipient,
            User actor,
            Post post,
            UserMention mention
    ) {
        Notification notification = base(recipient, actor, NotificationType.MENTION);
        notification.post = post;
        notification.mention = mention;
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
        if (this.createdAt == null) {
            this.createdAt = now();
        }
        if (this.eventAt == null) {
            this.eventAt = this.createdAt;
        }
    }

    public void markRead(OffsetDateTime readAt) {
        if (this.readAt == null) {
            this.readAt = readAt.truncatedTo(ChronoUnit.MICROS);
        }
    }

    public void refreshDirectMessage(User actor, DirectMessage message, boolean unread) {
        this.actor = actor;
        this.message = message;
        this.eventAt = message.getCreatedAt() != null
                ? message.getCreatedAt().truncatedTo(ChronoUnit.MICROS)
                : now();
        this.readAt = unread ? null : now();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
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

    public PostRepost getPostRepost() {
        return postRepost;
    }

    public MessageConversation getConversation() {
        return conversation;
    }

    public DirectMessage getMessage() {
        return message;
    }

    public MentoringApplication getMentoringApplication() {
        return mentoringApplication;
    }

    public MentoringReview getMentoringReview() {
        return mentoringReview;
    }

    public UserMention getMention() {
        return mention;
    }

    public OffsetDateTime getReadAt() {
        return readAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getEventAt() {
        return eventAt != null ? eventAt : createdAt;
    }
}
