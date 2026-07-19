package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.bookmark.repository.BookmarkCollectionItemRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostBookmark;
import com.gamerin.backend.domain.post.repository.PostBookmarkRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@ExtendWith(MockitoExtension.class)
class PostBookmarkCommandServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PostRepository postRepository;
    @Mock
    private PostBookmarkRepository postBookmarkRepository;
    @Mock
    private BookmarkCollectionItemRepository itemRepository;

    private PostBookmarkCommandService service;

    @BeforeEach
    void setUp() {
        service = new PostBookmarkCommandService(
                userRepository,
                postRepository,
                postBookmarkRepository,
                itemRepository
        );
    }

    @Test
    void bookmarkCreatesCanonicalBookmarkAfterLockingUser() {
        User user = user();
        Post post = post(user);
        CustomUserPrincipal principal = CustomUserPrincipal.from(user);
        when(userRepository.findActiveByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(postRepository.findAccessibleByIdForShare(post.getId())).thenReturn(Optional.of(post));
        when(postBookmarkRepository.findByPostIdAndUserId(post.getId(), user.getId()))
                .thenReturn(Optional.empty());

        service.bookmark(principal, post.getId());

        verify(postBookmarkRepository).save(any(PostBookmark.class));
    }

    @Test
    void repeatedBookmarkDoesNotCreateAnotherRow() {
        User user = user();
        Post post = post(user);
        PostBookmark bookmark = bookmark(post, user);
        CustomUserPrincipal principal = CustomUserPrincipal.from(user);
        when(userRepository.findActiveByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(postRepository.findAccessibleByIdForShare(post.getId())).thenReturn(Optional.of(post));
        when(postBookmarkRepository.findByPostIdAndUserId(post.getId(), user.getId()))
                .thenReturn(Optional.of(bookmark));

        service.bookmark(principal, post.getId());

        verify(postBookmarkRepository, never()).save(any());
    }

    @Test
    void unbookmarkDeletesCollectionRelationshipsBeforeCanonicalBookmark() {
        User user = user();
        Post post = post(user);
        PostBookmark bookmark = bookmark(post, user);
        CustomUserPrincipal principal = CustomUserPrincipal.from(user);
        when(userRepository.findActiveByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(postRepository.findAccessibleByIdForShare(post.getId())).thenReturn(Optional.of(post));
        when(postBookmarkRepository.findByPostIdAndUserId(post.getId(), user.getId()))
                .thenReturn(Optional.of(bookmark));

        service.unbookmark(principal, post.getId());

        InOrder order = inOrder(itemRepository, postBookmarkRepository);
        order.verify(itemRepository).deleteAllByPostBookmarkId(bookmark.getId());
        order.verify(postBookmarkRepository).delete(bookmark);
    }

    @Test
    void missingBookmarkCanBeRemovedRepeatedly() {
        User user = user();
        Post post = post(user);
        CustomUserPrincipal principal = CustomUserPrincipal.from(user);
        when(userRepository.findActiveByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(postRepository.findAccessibleByIdForShare(post.getId())).thenReturn(Optional.of(post));
        when(postBookmarkRepository.findByPostIdAndUserId(post.getId(), user.getId()))
                .thenReturn(Optional.empty());

        service.unbookmark(principal, post.getId());

        verify(itemRepository, never()).deleteAllByPostBookmarkId(any());
        verify(postBookmarkRepository, never()).delete(any());
    }

    @Test
    void missingPrincipalReturnsUnauthorized() {
        assertThatThrownBy(() -> service.bookmark(null, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private User user() {
        User user = User.createLocal("bookmark@example.com", "bookmark-user", "Bookmark", "hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private Post post(User author) {
        Post post = Post.create(author, "saved post");
        ReflectionTestUtils.setField(post, "id", UUID.randomUUID());
        return post;
    }

    private PostBookmark bookmark(Post post, User user) {
        PostBookmark bookmark = PostBookmark.create(post, user);
        ReflectionTestUtils.setField(bookmark, "id", UUID.randomUUID());
        return bookmark;
    }
}
