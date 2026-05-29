package com.gamerin.backend.domain.post.service;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.dto.response.PostCardResponse;
import com.gamerin.backend.domain.post.dto.response.ProfileMediaItemResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostBookmark;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.repository.PostQueryRepository;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@Service
@Transactional(readOnly = true)
public class FeedService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int DEFAULT_MEDIA_PAGE_SIZE = 24;
    private static final int MAX_PAGE_SIZE = 50;

    private final UserRepository userRepository;
    private final PostQueryRepository postQueryRepository;
    private final PostResponseAssembler postResponseAssembler;

    public FeedService(
            UserRepository userRepository,
            PostQueryRepository postQueryRepository,
            PostResponseAssembler postResponseAssembler
    ) {
        this.userRepository = userRepository;
        this.postQueryRepository = postQueryRepository;
        this.postResponseAssembler = postResponseAssembler;
    }

    public CursorPageResponse<PostCardResponse> getFeed(
            CustomUserPrincipal principal,
            String tab,
            String cursor,
            int size
    ) {
        UUID viewerId = getCurrentUserId(principal);
        String normalizedTab = normalizeTab(tab);
        int pageSize = clampSize(size, DEFAULT_PAGE_SIZE);
        boolean followingOnly = "following".equals(normalizedTab);

        List<Post> loadedPosts = postQueryRepository.findFeedPosts(viewerId, followingOnly, cursor, pageSize + 1);
        boolean hasNext = loadedPosts.size() > pageSize;
        List<Post> pagePosts = hasNext ? loadedPosts.subList(0, pageSize) : loadedPosts;
        List<PostCardResponse> items = postResponseAssembler.toPostCards(pagePosts, viewerId);

        return new CursorPageResponse<>(items, buildPostCursor(pagePosts, hasNext), hasNext);
    }

    public CursorPageResponse<PostCardResponse> getUserPosts(
            CustomUserPrincipal principal,
            String handle,
            String cursor,
            int size
    ) {
        UUID viewerId = getCurrentUserId(principal);
        ensureTargetUserExists(handle);

        int pageSize = clampSize(size, DEFAULT_PAGE_SIZE);
        List<Post> loadedPosts = postQueryRepository.findUserPosts(handle, cursor, pageSize + 1);
        boolean hasNext = loadedPosts.size() > pageSize;
        List<Post> pagePosts = hasNext ? loadedPosts.subList(0, pageSize) : loadedPosts;
        List<PostCardResponse> items = postResponseAssembler.toPostCards(pagePosts, viewerId);

        return new CursorPageResponse<>(items, buildPostCursor(pagePosts, hasNext), hasNext);
    }

    public CursorPageResponse<ProfileMediaItemResponse> getUserMedia(
            CustomUserPrincipal principal,
            String handle,
            String cursor,
            int size
    ) {
        getCurrentUserId(principal);
        ensureTargetUserExists(handle);

        int pageSize = clampSize(size, DEFAULT_MEDIA_PAGE_SIZE);
        List<PostMedia> loadedMedia = postQueryRepository.findUserMedia(handle, cursor, pageSize + 1);
        boolean hasNext = loadedMedia.size() > pageSize;
        List<PostMedia> pageMedia = hasNext ? loadedMedia.subList(0, pageSize) : loadedMedia;

        return new CursorPageResponse<>(
                postResponseAssembler.toProfileMediaItems(pageMedia),
                buildMediaCursor(pageMedia, hasNext),
                hasNext
        );
    }

    public CursorPageResponse<PostCardResponse> getMyBookmarks(
            CustomUserPrincipal principal,
            String cursor,
            int size
    ) {
        UUID viewerId = getCurrentUserId(principal);
        int pageSize = clampSize(size, DEFAULT_PAGE_SIZE);
        List<PostBookmark> loadedBookmarks = postQueryRepository.findBookmarkedPosts(viewerId, cursor, pageSize + 1);
        boolean hasNext = loadedBookmarks.size() > pageSize;
        List<PostBookmark> pageBookmarks = hasNext ? loadedBookmarks.subList(0, pageSize) : loadedBookmarks;
        List<Post> posts = pageBookmarks.stream()
                .map(PostBookmark::getPost)
                .toList();

        return new CursorPageResponse<>(
                postResponseAssembler.toPostCards(posts, viewerId),
                buildBookmarkCursor(pageBookmarks, hasNext),
                hasNext
        );
    }

    private UUID getCurrentUserId(CustomUserPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }

        return userRepository.findByIdAndDeletedAtIsNull(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found."))
                .getId();
    }

    private void ensureTargetUserExists(String handle) {
        userRepository.findByHandleAndDeletedAtIsNull(handle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }

    private String normalizeTab(String tab) {
        if (tab == null || tab.isBlank()) {
            return "all";
        }

        String normalized = tab.trim().toLowerCase();
        if (!normalized.equals("all") && !normalized.equals("following")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 피드 탭입니다.");
        }
        return normalized;
    }

    private int clampSize(int requested, int fallback) {
        if (requested <= 0) {
            return fallback;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private String buildPostCursor(List<Post> posts, boolean hasNext) {
        if (!hasNext || posts.isEmpty()) {
            return null;
        }

        Post last = posts.get(posts.size() - 1);
        return last.getCreatedAt() + "|" + last.getId();
    }

    private String buildMediaCursor(List<PostMedia> mediaItems, boolean hasNext) {
        if (!hasNext || mediaItems.isEmpty()) {
            return null;
        }

        PostMedia last = mediaItems.get(mediaItems.size() - 1);
        return last.getPost().getCreatedAt()
                + "|" + last.getPost().getId()
                + "|" + last.getSortOrder()
                + "|" + last.getId();
    }

    private String buildBookmarkCursor(List<PostBookmark> bookmarks, boolean hasNext) {
        if (!hasNext || bookmarks.isEmpty()) {
            return null;
        }

        PostBookmark last = bookmarks.get(bookmarks.size() - 1);
        return last.getCreatedAt() + "|" + last.getId();
    }
}
