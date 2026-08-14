package com.gamerin.backend.domain.notification.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gamerin.backend.domain.follow.entity.Follow;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.MentoringReview;
import com.gamerin.backend.domain.message.entity.DirectMessage;
import com.gamerin.backend.domain.message.entity.MessageConversation;
import com.gamerin.backend.domain.notification.entity.Notification;
import com.gamerin.backend.domain.notification.entity.NotificationType;
import com.gamerin.backend.domain.notification.repository.NotificationRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.entity.PostLike;
import com.gamerin.backend.domain.repost.entity.PostRepost;
import com.gamerin.backend.domain.user.entity.User;

@Service
@Transactional
public class NotificationCommandService {

    private final NotificationRepository notificationRepository;

    public NotificationCommandService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public void createLike(PostLike postLike, Post post, User actor) {
        User recipient = post.getAuthor();
        if (recipient.getId().equals(actor.getId())) {
            return;
        }
        notificationRepository.save(Notification.like(recipient, actor, post, postLike));
    }

    public void createComment(PostComment comment, Post post, User actor) {
        User recipient = post.getAuthor();
        if (recipient.getId().equals(actor.getId())) {
            return;
        }
        notificationRepository.save(Notification.comment(recipient, actor, post, comment));
    }

    public void createFollow(Follow follow, User follower, User followee) {
        if (follower.getId().equals(followee.getId())) {
            return;
        }
        notificationRepository.save(Notification.follow(followee, follower, follow));
    }

    public void createRepost(PostRepost repost, Post post, User actor) {
        User recipient = post.getAuthor();
        if (recipient.getId().equals(actor.getId())) {
            return;
        }
        notificationRepository.save(Notification.repost(recipient, actor, post, repost));
    }

    public void createOrRefreshDirectMessage(
            User recipient,
            User actor,
            MessageConversation conversation,
            DirectMessage message
    ) {
        if (recipient.getId().equals(actor.getId())) {
            return;
        }
        notificationRepository.findDirectMessageForUpdate(recipient.getId(), conversation.getId())
                .ifPresentOrElse(
                        notification -> notification.refreshDirectMessage(actor, message, true),
                        () -> notificationRepository.save(
                                Notification.directMessage(recipient, actor, conversation, message)
                        )
                );
    }

    public void syncDirectMessage(
            User recipient,
            MessageConversation conversation,
            DirectMessage latestMessage,
            boolean unread
    ) {
        if (latestMessage == null) {
            removeDirectMessage(recipient.getId(), conversation.getId());
            return;
        }

        User actor = latestMessage.getSender();
        notificationRepository.findDirectMessageForUpdate(recipient.getId(), conversation.getId())
                .ifPresentOrElse(
                        notification -> notification.refreshDirectMessage(actor, latestMessage, unread),
                        () -> {
                            if (!unread) {
                                return;
                            }
                            Notification notification = Notification.directMessage(
                                    recipient,
                                    actor,
                                    conversation,
                                    latestMessage
                            );
                            notificationRepository.save(notification);
                        }
                );
    }

    public void markDirectMessageRead(UUID recipientId, UUID conversationId) {
        notificationRepository.findDirectMessageForUpdate(recipientId, conversationId)
                .ifPresent(notification -> notification.markRead(java.time.OffsetDateTime.now()));
    }

    public void createMentoringApplication(MentoringApplication application, User mentee, User mentor) {
        createMentoring(NotificationType.MENTORING_APPLICATION, application, mentee, mentor);
    }

    public void createMentoringCancelled(MentoringApplication application, User mentee, User mentor) {
        createMentoring(NotificationType.MENTORING_CANCELLED, application, mentee, mentor);
    }

    public void createMentoringAccepted(MentoringApplication application, User mentor, User mentee) {
        createMentoring(NotificationType.MENTORING_ACCEPTED, application, mentor, mentee);
    }

    public void createMentoringRejected(MentoringApplication application, User mentor, User mentee) {
        createMentoring(NotificationType.MENTORING_REJECTED, application, mentor, mentee);
    }

    public void createMentoringStarted(MentoringApplication application, User mentor, User mentee) {
        createMentoring(NotificationType.MENTORING_STARTED, application, mentor, mentee);
    }

    public void createMentoringFinished(MentoringApplication application, User mentor, User mentee) {
        createMentoring(NotificationType.MENTORING_FINISHED, application, mentor, mentee);
    }

    public void createMentoringCompleted(MentoringApplication application, User actor, User recipient) {
        createMentoring(NotificationType.MENTORING_COMPLETED, application, actor, recipient);
    }

    public void createMentoringReview(
            MentoringApplication application,
            MentoringReview review,
            User mentee,
            User mentor
    ) {
        notificationRepository.save(Notification.mentoringReview(mentor, mentee, application, review));
    }

    private void createMentoring(
            NotificationType type,
            MentoringApplication application,
            User actor,
            User recipient
    ) {
        if (actor != null && actor.getId().equals(recipient.getId())) {
            return;
        }
        notificationRepository.save(Notification.mentoring(recipient, actor, type, application));
    }

    public void removeLike(UUID postId, UUID actorId) {
        notificationRepository.deleteLikeNotification(postId, actorId);
    }

    public void removeComment(UUID commentId) {
        notificationRepository.deleteCommentNotification(commentId);
    }

    public void removeFollow(UUID followerId, UUID followeeId) {
        notificationRepository.deleteFollowNotification(followerId, followeeId);
    }

    public void removeRepost(UUID postId, UUID actorId) {
        notificationRepository.deleteRepostNotification(postId, actorId);
    }

    public void removeDirectMessage(UUID recipientId, UUID conversationId) {
        notificationRepository.deleteDirectMessageNotification(recipientId, conversationId);
    }
}
