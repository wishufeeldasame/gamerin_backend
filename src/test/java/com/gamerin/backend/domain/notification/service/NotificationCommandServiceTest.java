package com.gamerin.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamerin.backend.domain.follow.entity.Follow;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
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
        service.removeRepost(postId, actorId);
        service.removeDirectMessage(followeeId, postId);

        verify(notificationRepository).deleteLikeNotification(postId, actorId);
        verify(notificationRepository).deleteCommentNotification(commentId);
        verify(notificationRepository).deleteFollowNotification(actorId, followeeId);
        verify(notificationRepository).deleteRepostNotification(postId, actorId);
        verify(notificationRepository).deleteDirectMessageNotification(followeeId, postId);
    }

    @Test
    void createsRepostNotificationForPostAuthor() {
        User author = savedUser("author");
        User actor = savedUser("actor");
        Post post = savedPost(author);
        PostRepost repost = PostRepost.create(post, actor);

        service.createRepost(repost, post, actor);

        Notification notification = captureSavedNotification();
        assertThat(notification.getType()).isEqualTo(NotificationType.REPOST);
        assertThat(notification.getRecipient()).isSameAs(author);
        assertThat(notification.getActor()).isSameAs(actor);
        assertThat(notification.getPostRepost()).isSameAs(repost);
    }

    @Test
    void directMessagesRefreshOneConversationNotification() {
        User recipient = savedUser("recipient");
        User actor = savedUser("actor");
        MessageConversation conversation = savedConversation();
        DirectMessage first = savedMessage(conversation, actor, "first", OffsetDateTime.now().minusMinutes(1));
        DirectMessage second = savedMessage(conversation, actor, "second", OffsetDateTime.now());
        Notification existing = Notification.directMessage(recipient, actor, conversation, first);
        existing.markRead(OffsetDateTime.now());
        when(notificationRepository.findDirectMessageForUpdate(recipient.getId(), conversation.getId()))
                .thenReturn(Optional.of(existing));

        service.createOrRefreshDirectMessage(recipient, actor, conversation, second);

        assertThat(existing.getMessage()).isSameAs(second);
        assertThat(existing.getReadAt()).isNull();
        assertThat(existing.getEventAt()).isEqualTo(second.getCreatedAt().truncatedTo(ChronoUnit.MICROS));
        verify(notificationRepository, never()).save(any(Notification.class));
    }

    @Test
    void doesNotCreateHistoricalDirectMessageNotificationForReadFallback() {
        User recipient = savedUser("recipient");
        User actor = savedUser("actor");
        MessageConversation conversation = savedConversation();
        DirectMessage message = savedMessage(conversation, actor, "old", OffsetDateTime.now().minusDays(1));
        when(notificationRepository.findDirectMessageForUpdate(recipient.getId(), conversation.getId()))
                .thenReturn(Optional.empty());

        service.syncDirectMessage(recipient, conversation, message, false);

        verify(notificationRepository, never()).save(any(Notification.class));
    }

    @Test
    void automaticMentoringCompletionSupportsSystemActor() {
        User recipient = savedUser("mentor");
        MentoringApplication application = new MentoringApplication();

        service.createMentoringCompleted(application, null, recipient);

        Notification notification = captureSavedNotification();
        assertThat(notification.getType()).isEqualTo(NotificationType.MENTORING_COMPLETED);
        assertThat(notification.getRecipient()).isSameAs(recipient);
        assertThat(notification.getActor()).isNull();
        assertThat(notification.getMentoringApplication()).isSameAs(application);
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

    private MessageConversation savedConversation() {
        MessageConversation conversation = MessageConversation.createDirect(UUID.randomUUID().toString());
        ReflectionTestUtils.setField(conversation, "id", UUID.randomUUID());
        return conversation;
    }

    private DirectMessage savedMessage(
            MessageConversation conversation,
            User sender,
            String content,
            OffsetDateTime createdAt
    ) {
        DirectMessage message = DirectMessage.create(conversation, sender, content, null);
        ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(message, "createdAt", createdAt);
        return message;
    }
}
