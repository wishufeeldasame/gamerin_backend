package com.gamerin.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamerin.backend.domain.follow.entity.Follow;
import com.gamerin.backend.domain.notification.entity.Notification;
import com.gamerin.backend.domain.notification.entity.NotificationType;
import com.gamerin.backend.domain.notification.repository.NotificationRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.entity.PostLike;
import com.gamerin.backend.domain.user.entity.User;

@ExtendWith(MockitoExtension.class)
class NotificationCommandServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    private NotificationCommandService service;

    @BeforeEach
    void setUp() {
        service = new NotificationCommandService(notificationRepository);
    }

    @Test
    void createsLikeNotificationForPostAuthor() {
        User author = savedUser("author");
        User actor = savedUser("actor");
        Post post = savedPost(author);
        PostLike like = PostLike.create(post, actor);

        service.createLike(like, post, actor);

        Notification notification = captureSavedNotification();
        assertThat(notification.getType()).isEqualTo(NotificationType.LIKE);
        assertThat(notification.getRecipient()).isSameAs(author);
        assertThat(notification.getActor()).isSameAs(actor);
        assertThat(notification.getPost()).isSameAs(post);
    }

    @Test
    void doesNotCreateNotificationForOwnLikeOrComment() {
        User author = savedUser("author");
        Post post = savedPost(author);

        service.createLike(PostLike.create(post, author), post, author);
        service.createComment(PostComment.create(post, author, "hello"), post, author);

        verify(notificationRepository, never()).save(any(Notification.class));
    }

    @Test
    void createsCommentNotificationForPostAuthor() {
        User author = savedUser("author");
        User actor = savedUser("actor");
        Post post = savedPost(author);
        PostComment comment = PostComment.create(post, actor, "hello");

        service.createComment(comment, post, actor);

        Notification notification = captureSavedNotification();
        assertThat(notification.getType()).isEqualTo(NotificationType.COMMENT);
        assertThat(notification.getRecipient()).isSameAs(author);
        assertThat(notification.getActor()).isSameAs(actor);
        assertThat(notification.getComment()).isSameAs(comment);
    }

    @Test
    void createsFollowNotificationForFollowee() {
        User follower = savedUser("follower");
        User followee = savedUser("followee");
        Follow follow = Follow.create(follower, followee);

        service.createFollow(follow, follower, followee);

        Notification notification = captureSavedNotification();
        assertThat(notification.getType()).isEqualTo(NotificationType.FOLLOW);
        assertThat(notification.getRecipient()).isSameAs(followee);
        assertThat(notification.getActor()).isSameAs(follower);
        assertThat(notification.getPost()).isNull();
    }

    @Test
    void cancellationDelegatesToSourceSpecificDeletes() {
        UUID postId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        UUID followeeId = UUID.randomUUID();

        service.removeLike(postId, actorId);
        service.removeComment(commentId);
        service.removeFollow(actorId, followeeId);

        verify(notificationRepository).deleteLikeNotification(postId, actorId);
        verify(notificationRepository).deleteCommentNotification(commentId);
        verify(notificationRepository).deleteFollowNotification(actorId, followeeId);
    }

    private Notification captureSavedNotification() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        return captor.getValue();
    }

    private User savedUser(String handle) {
        User user = User.createLocal(handle + "@example.com", handle, handle, "password");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private Post savedPost(User author) {
        Post post = Post.create(author, "post");
        ReflectionTestUtils.setField(post, "id", UUID.randomUUID());
        return post;
    }
}
