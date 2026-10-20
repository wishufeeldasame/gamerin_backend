package com.gamerin.backend.domain.mention.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamerin.backend.domain.mention.entity.UserMention;
import com.gamerin.backend.domain.mention.repository.MentionCommandRepository;
import com.gamerin.backend.domain.mention.repository.MentionCommandRepository.MentionAttachResult;
import com.gamerin.backend.domain.notification.service.NotificationCommandService;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class MentionServiceTest {

    @Mock
    private MentionCommandRepository mentionCommandRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationCommandService notificationCommandService;

    private MentionService mentionService;

    @BeforeEach
    void setUp() {
        mentionService = new MentionService(
                new MentionParser(),
                mentionCommandRepository,
                userRepository,
                notificationCommandService
        );
    }

    @Test
    void postMentionResolvesUsersInBatchAndDeduplicatesByUser() {
        User actor = savedUser("actor");
        User target = savedUser("target");
        Post post = savedPost(actor, "@target hello @target.");
        UserMention relation = mock(UserMention.class);

        when(userRepository.findActiveByHandleIn(any())).thenReturn(List.of(target));
        when(mentionCommandRepository.attachToPost(post.getId(), target.getId()))
                .thenReturn(new MentionAttachResult(relation, true));

        mentionService.attachToPost(post);

        verify(mentionCommandRepository).attachToPost(post.getId(), target.getId());
        verify(notificationCommandService).createMention(relation, post, actor, target);
    }

    @Test
    void exactHandleEndingWithDotWinsBeforePunctuationFallback() {
        User actor = savedUser("actor");
        User exactTarget = savedUser("target.");
        User fallbackTarget = savedUser("target");
        Post post = savedPost(actor, "hello @target.");
        UserMention relation = mock(UserMention.class);

        when(userRepository.findActiveByHandleIn(any())).thenReturn(List.of(fallbackTarget, exactTarget));
        when(mentionCommandRepository.attachToPost(post.getId(), exactTarget.getId()))
                .thenReturn(new MentionAttachResult(relation, true));

        mentionService.attachToPost(post);

        verify(mentionCommandRepository).attachToPost(post.getId(), exactTarget.getId());
        verify(mentionCommandRepository, never()).attachToPost(post.getId(), fallbackTarget.getId());
        verify(notificationCommandService).createMention(relation, post, actor, exactTarget);
    }

    @Test
    void selfMentionKeepsRelationWithoutCreatingNotification() {
        User actor = savedUser("actor");
        Post post = savedPost(actor, "hello @actor");
        UserMention relation = mock(UserMention.class);

        when(userRepository.findActiveByHandleIn(any())).thenReturn(List.of(actor));
        when(mentionCommandRepository.attachToPost(post.getId(), actor.getId()))
                .thenReturn(new MentionAttachResult(relation, true));

        mentionService.attachToPost(post);

        verify(mentionCommandRepository).attachToPost(post.getId(), actor.getId());
        verify(notificationCommandService, never()).createMention(any(), any(), any(), any());
    }

    @Test
    void commentMentionOfPostAuthorDoesNotDuplicateCommentNotification() {
        User author = savedUser("author");
        User actor = savedUser("actor");
        Post post = savedPost(author, "post");
        PostComment comment = savedComment(post, actor, "hello @author");
        UserMention relation = mock(UserMention.class);

        when(userRepository.findActiveByHandleIn(any())).thenReturn(List.of(author));
        when(mentionCommandRepository.attachToComment(comment.getId(), author.getId()))
                .thenReturn(new MentionAttachResult(relation, true));

        mentionService.attachToComment(comment);

        verify(mentionCommandRepository).attachToComment(comment.getId(), author.getId());
        verify(notificationCommandService, never()).createMention(any(), any(), any(), any());
    }

    @Test
    void unknownDeletedOrExistingRelationDoesNotCreateAnotherNotification() {
        User actor = savedUser("actor");
        User target = savedUser("target");
        Post post = savedPost(actor, "@unknown @target");
        UserMention relation = mock(UserMention.class);

        when(userRepository.findActiveByHandleIn(any())).thenReturn(List.of(target));
        when(mentionCommandRepository.attachToPost(post.getId(), target.getId()))
                .thenReturn(new MentionAttachResult(relation, false));

        mentionService.attachToPost(post);

        verify(mentionCommandRepository).attachToPost(post.getId(), target.getId());
        verify(notificationCommandService, never()).createMention(any(), any(), any(), any());
    }

    @Test
    void commentRemovalDeletesStoredMentionRelations() {
        UUID commentId = UUID.randomUUID();

        mentionService.removeForComment(commentId);

        verify(mentionCommandRepository).deleteByCommentId(commentId);
    }

    private User savedUser(String handle) {
        User user = User.createLocal(handle + "@example.com", handle, handle, "password");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private Post savedPost(User author, String content) {
        Post post = Post.create(author, content);
        ReflectionTestUtils.setField(post, "id", UUID.randomUUID());
        return post;
    }

    private PostComment savedComment(Post post, User author, String content) {
        PostComment comment = PostComment.create(post, author, content);
        ReflectionTestUtils.setField(comment, "id", UUID.randomUUID());
        return comment;
    }
}
