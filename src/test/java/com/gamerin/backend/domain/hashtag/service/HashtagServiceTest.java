package com.gamerin.backend.domain.hashtag.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamerin.backend.domain.hashtag.model.ParsedHashtag;
import com.gamerin.backend.domain.hashtag.repository.HashtagCommandRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.user.entity.User;

@ExtendWith(MockitoExtension.class)
class HashtagServiceTest {

    @Mock
    private HashtagParser hashtagParser;

    @Mock
    private HashtagCommandRepository hashtagCommandRepository;

    @Test
    void attachesParsedHashtagsToSavedPostInAppearanceOrder() {
        UUID postId = UUID.randomUUID();
        UUID pubgId = UUID.randomUUID();
        UUID rankedId = UUID.randomUUID();
        Post post = savedPost(postId, "#PUBG #ranked");
        when(hashtagParser.parse(post.getContent())).thenReturn(List.of(
                new ParsedHashtag("PUBG", "PUBG"),
                new ParsedHashtag("ranked", "ranked")));
        when(hashtagCommandRepository.findOrCreate("PUBG", "PUBG")).thenReturn(pubgId);
        when(hashtagCommandRepository.findOrCreate("ranked", "ranked")).thenReturn(rankedId);

        new HashtagService(hashtagParser, hashtagCommandRepository).attachToPost(post);

        InOrder order = inOrder(hashtagCommandRepository);
        order.verify(hashtagCommandRepository).findOrCreate("PUBG", "PUBG");
        order.verify(hashtagCommandRepository).attachToPost(postId, pubgId);
        order.verify(hashtagCommandRepository).findOrCreate("ranked", "ranked");
        order.verify(hashtagCommandRepository).attachToPost(postId, rankedId);
    }

    @Test
    void doesNotWriteRelationsWhenContentHasNoHashtags() {
        Post post = savedPost(UUID.randomUUID(), "plain content");
        when(hashtagParser.parse(post.getContent())).thenReturn(List.of());

        new HashtagService(hashtagParser, hashtagCommandRepository).attachToPost(post);

        verify(hashtagCommandRepository, never()).findOrCreate(any(), any());
        verify(hashtagCommandRepository, never()).attachToPost(any(), any());
    }

    @Test
    void rejectsTransientPostBeforeAnyDatabaseWrite() {
        Post post = Post.create(User.createLocal(
                "test@example.com",
                "tester",
                "Tester",
                "password"), "#tag");

        assertThatThrownBy(() -> new HashtagService(hashtagParser, hashtagCommandRepository).attachToPost(post))
                .isInstanceOf(IllegalArgumentException.class);

        verify(hashtagCommandRepository, never()).findOrCreate(any(), any());
    }

    /**
     * KP-C3 (#92): 게시물 수정 시 기존 post_hashtags 삭제 후 새 해시태그를 연결하는지 검증한다.
     */
    @Test
    void syncForPostDeletesExistingRelationsBeforeReattachingParsedHashtags() {
        UUID postId = UUID.randomUUID();
        UUID valorantId = UUID.randomUUID();
        Post post = savedPost(postId, "#Valorant");
        when(hashtagParser.parse(post.getContent())).thenReturn(List.of(
                new ParsedHashtag("Valorant", "valorant")));
        when(hashtagCommandRepository.findOrCreate("Valorant", "valorant")).thenReturn(valorantId);

        new HashtagService(hashtagParser, hashtagCommandRepository).syncForPost(post);

        InOrder order = inOrder(hashtagCommandRepository);
        order.verify(hashtagCommandRepository).deleteByPostId(postId);
        order.verify(hashtagCommandRepository).findOrCreate("Valorant", "valorant");
        order.verify(hashtagCommandRepository).attachToPost(postId, valorantId);
    }

    private Post savedPost(UUID postId, String content) {
        User user = User.createLocal("test@example.com", "tester", "Tester", "password");
        Post post = Post.create(user, content);
        ReflectionTestUtils.setField(post, "id", postId);
        return post;
    }
}