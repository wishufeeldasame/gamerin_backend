package com.gamerin.backend.domain.post.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.bookmark.repository.BookmarkCollectionItemRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostBookmark;
import com.gamerin.backend.domain.post.repository.PostBookmarkRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@Service
public class PostBookmarkCommandService {

    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final PostBookmarkRepository postBookmarkRepository;
    private final BookmarkCollectionItemRepository bookmarkCollectionItemRepository;

    public PostBookmarkCommandService(
            UserRepository userRepository,
            PostRepository postRepository,
            PostBookmarkRepository postBookmarkRepository,
            BookmarkCollectionItemRepository bookmarkCollectionItemRepository
    ) {
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.postBookmarkRepository = postBookmarkRepository;
        this.bookmarkCollectionItemRepository = bookmarkCollectionItemRepository;
    }

    @Transactional
    public void bookmark(CustomUserPrincipal principal, UUID postId) {
        User user = lockCurrentUser(principal);
        Post post = lockAccessiblePost(postId);
        getOrCreate(user, post);
    }

    @Transactional
    public void unbookmark(CustomUserPrincipal principal, UUID postId) {
        User user = lockCurrentUser(principal);
        lockAccessiblePost(postId);

        postBookmarkRepository.findByPostIdAndUserId(postId, user.getId())
                .ifPresent(bookmark -> {
                    bookmarkCollectionItemRepository.deleteAllByPostBookmarkId(bookmark.getId());
                    postBookmarkRepository.delete(bookmark);
                });
    }

    @Transactional
    public PostBookmark getOrCreate(User user, Post post) {
        return postBookmarkRepository.findByPostIdAndUserId(post.getId(), user.getId())
                .orElseGet(() -> postBookmarkRepository.save(PostBookmark.create(post, user)));
    }

    public User lockCurrentUser(CustomUserPrincipal principal) {
        UUID userId = requireUserId(principal);
        return userRepository.findActiveByIdForUpdate(userId)
                .orElseThrow(this::authenticatedUserNotFound);
    }

    @Transactional(readOnly = true)
    public User getCurrentUser(CustomUserPrincipal principal) {
        UUID userId = requireUserId(principal);
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(this::authenticatedUserNotFound);
    }

    private UUID requireUserId(CustomUserPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        return principal.getUserId();
    }

    private ResponseStatusException authenticatedUserNotFound() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authenticated user not found."
        );
    }

    public Post getAccessiblePost(UUID postId) {
        return postRepository.findAccessibleById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found."));
    }

    public Post lockAccessiblePost(UUID postId) {
        return postRepository.findAccessibleByIdForShare(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found."));
    }
}
