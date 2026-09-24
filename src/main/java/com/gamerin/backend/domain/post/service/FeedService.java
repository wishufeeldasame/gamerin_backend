package com.gamerin.backend.domain.post.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.bookmark.repository.BookmarkCollectionQueryRepository;
import com.gamerin.backend.domain.post.dto.response.PostCardResponse;
import com.gamerin.backend.domain.post.dto.response.ProfileMediaItemResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostBookmark;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.repository.PostQueryRepository;
import com.gamerin.backend.domain.repost.model.PostTimelineItem;
import com.gamerin.backend.domain.repost.model.TimelineCursor;
import com.gamerin.backend.domain.repost.repository.PostTimelineQueryRepository;
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
    private final BookmarkCollectionQueryRepository bookmarkCollectionQueryRepository;
    private final PostTimelineQueryRepository postTimelineQueryRepository;
    private final PostResponseAssembler postResponseAssembler;

    public FeedService(
            UserRepository userRepository,
            PostQueryRepository postQueryRepository,
            BookmarkCollectionQueryRepository bookmarkCollectionQueryRepository,
            PostTimelineQueryRepository postTimelineQueryRepository,
            PostResponseAssembler postResponseAssembler
    ) {
        this.userRepository = userRepository;
        this.postQueryRepository = postQueryRepository;
        this.bookmarkCollectionQueryRepository = bookmarkCollectionQueryRepository;
        this.postTimelineQueryRepository = postTimelineQueryRepository;
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
        TimelineCursor timelineCursor = TimelineCursor.parseOrStart(cursor);

        List<PostTimelineItem> loadedItems = postTimelineQueryRepository.findFeedItems(
                viewerId,
                followingOnly,
                timelineCursor,
                pageSize + 1
        );
        boolean hasNext = loadedItems.size() > pageSize;
        List<PostTimelineItem> pageItems = hasNext ? loadedItems.subList(0, pageSize) : loadedItems;
        List<PostCardResponse> items = postResponseAssembler.toTimelinePostCards(pageItems, viewerId);

        return new CursorPageResponse<>(items, buildTimelineCursor(pageItems, timelineCursor, hasNext), hasNext);
    }

    public CursorPageResponse<PostCardResponse> getUserPosts(
            CustomUserPrincipal principal,
            String handle,
            String cursor,
            int size
    ) {
        UUID viewerId = getCurrentUserId(principal);
        UUID targetUserId = getTargetUserId(handle);

        int pageSize = clampSize(size, DEFAULT_PAGE_SIZE);
        TimelineCursor timelineCursor = TimelineCursor.parseOrStart(cursor);
        List<PostTimelineItem> loadedItems = postTimelineQueryRepository.findUserItems(
                targetUserId,
                timelineCursor,
                pageSize + 1
        );
        boolean hasNext = loadedItems.size() > pageSize;
        List<PostTimelineItem> pageItems = hasNext ? loadedItems.subList(0, pageSize) : loadedItems;
        List<PostCardResponse> items = postResponseAssembler.toTimelinePostCards(pageItems, viewerId);

        return new CursorPageResponse<>(items, buildTimelineCursor(pageItems, timelineCursor, hasNext), hasNext);
    }

    public CursorPageResponse<ProfileMediaItemResponse> getUserMedia(
            CustomUserPrincipal principal,
            String handle,
            String cursor,
            int size
    ) {
        getCurrentUserId(principal);
        getTargetUserId(handle);

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
        return getMyBookmarks(principal, "all", cursor, size, null, false);
    }

    public CursorPageResponse<PostCardResponse> getMyBookmarks(
            CustomUserPrincipal principal,
            String scope,
            String cursor,
            int size,
            String keyword,
            boolean mediaOnly
    ) {
        UUID viewerId = getCurrentUserId(principal);
        int pageSize = clampSize(size, DEFAULT_PAGE_SIZE);
        String normalizedScope = normalizeBookmarkScope(scope);
        List<PostBookmark> loadedBookmarks = "unclassified".equals(normalizedScope)
                ? bookmarkCollectionQueryRepository.findUnclassifiedBookmarks(
                        viewerId,
                        cursor,
                        pageSize + 1,
                        keyword,
                        mediaOnly
                )
                : postQueryRepository.findBookmarkedPosts(
                        viewerId,
                        cursor,
                        pageSize + 1,
                        keyword,
                        mediaOnly
                );
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

    private String normalizeBookmarkScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "all";
        }
        String normalized = scope.strip().toLowerCase(Locale.ROOT);
        if (!normalized.equals("all") && !normalized.equals("unclassified")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid bookmark scope.");
        }
        return normalized;
    }

    private UUID getCurrentUserId(CustomUserPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }

        return userRepository.findByIdAndDeletedAtIsNull(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found."))
                .getId();
    }

    private UUID getTargetUserId(String handle) {
        return userRepository.findByHandleAndDeletedAtIsNull(handle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."))
                .getId();
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

    private String buildTimelineCursor(
            List<PostTimelineItem> items,
            TimelineCursor cursor,
            boolean hasNext
    ) {
        if (!hasNext || items.isEmpty()) {
            return null;
        }

        PostTimelineItem last = items.get(items.size() - 1);
        return cursor.next(last.activityAt(), last.post().getId());
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
