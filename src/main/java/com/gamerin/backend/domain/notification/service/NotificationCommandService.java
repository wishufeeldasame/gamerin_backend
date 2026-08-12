package com.gamerin.backend.domain.notification.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gamerin.backend.domain.follow.entity.Follow;
import com.gamerin.backend.domain.notification.entity.Notification;
import com.gamerin.backend.domain.notification.repository.NotificationRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.entity.PostLike;
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

    public void removeLike(UUID postId, UUID actorId) {
        notificationRepository.deleteLikeNotification(postId, actorId);
    }

    public void removeComment(UUID commentId) {
        notificationRepository.deleteCommentNotification(commentId);
    }

    public void removeFollow(UUID followerId, UUID followeeId) {
        notificationRepository.deleteFollowNotification(followerId, followeeId);
    }
}
