package com.gamerin.backend.domain.search.service;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.hashtag.dto.response.HashtagSummaryResponse;
import com.gamerin.backend.domain.hashtag.model.HashtagSummary;
import com.gamerin.backend.domain.hashtag.repository.HashtagQueryRepository;
import com.gamerin.backend.domain.hashtag.service.HashtagParser;
import com.gamerin.backend.domain.post.dto.response.PostCardResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.service.PostResponseAssembler;
import com.gamerin.backend.domain.search.dto.response.SearchOverviewResponse;
import com.gamerin.backend.domain.search.dto.response.SearchSectionResponse;
import com.gamerin.backend.domain.search.model.AccountSearchCursor;
import com.gamerin.backend.domain.search.model.PostSearchCursor;
import com.gamerin.backend.domain.search.repository.SearchQueryRepository;
import com.gamerin.backend.domain.user.dto.response.SimpleUserProfileResponse;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@Service
@Transactional(readOnly = true)
public class SearchService {

    private static final int MAX_QUERY_LENGTH = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PREVIEW_SIZE = 5;
    private static final int MAX_PREVIEW_SIZE = 10;
    private static final int DEFAULT_HASHTAG_SIZE = 20;
    private static final int MAX_HASHTAG_SIZE = 20;

    private final SearchQueryRepository searchQueryRepository;
    private final HashtagQueryRepository hashtagQueryRepository;
    private final HashtagParser hashtagParser;
    private final PostResponseAssembler postResponseAssembler;
    private final UserRepository userRepository;

    public SearchService(
            SearchQueryRepository searchQueryRepository,
            HashtagQueryRepository hashtagQueryRepository,
            HashtagParser hashtagParser,
            PostResponseAssembler postResponseAssembler,
            UserRepository userRepository
    ) {
        this.searchQueryRepository = searchQueryRepository;
        this.hashtagQueryRepository = hashtagQueryRepository;
        this.hashtagParser = hashtagParser;
        this.postResponseAssembler = postResponseAssembler;
        this.userRepository = userRepository;
    }

    public SearchOverviewResponse overview(
            CustomUserPrincipal principal,
            String query,
            int size
    ) {
        UUID viewerId = requireViewerId(principal);
        String keyword = normalizeQuery(query);
        int previewSize = clampSize(size, DEFAULT_PREVIEW_SIZE, MAX_PREVIEW_SIZE);

        List<User> loadedAccounts = searchQueryRepository.findActiveAccounts(
                keyword,
                null,
                previewSize + 1
        );
        List<Post> loadedPosts = searchQueryRepository.findActivePosts(
                keyword,
                null,
                previewSize + 1
        );
        List<HashtagSummary> loadedHashtags = findHashtags(keyword, previewSize + 1);

        return new SearchOverviewResponse(
                keyword,
                accountSection(loadedAccounts, previewSize),
                postSection(loadedPosts, viewerId, previewSize),
                hashtagSection(loadedHashtags, previewSize)
        );
    }

    public CursorPageResponse<SimpleUserProfileResponse> searchAccounts(
            CustomUserPrincipal principal,
            String query,
            String cursor,
            int size
    ) {
        requireViewerId(principal);
        String keyword = normalizeQuery(query);
        int pageSize = clampSize(size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        List<User> loaded = searchQueryRepository.findActiveAccounts(
                keyword,
                AccountSearchCursor.parse(cursor),
                pageSize + 1
        );
        boolean hasNext = loaded.size() > pageSize;
        List<User> page = hasNext ? loaded.subList(0, pageSize) : loaded;
        String nextCursor = hasNext && !page.isEmpty()
                ? AccountSearchCursor.encode(page.getLast().getHandle(), page.getLast().getId())
                : null;

        return new CursorPageResponse<>(
                page.stream().map(SimpleUserProfileResponse::from).toList(),
                nextCursor,
                hasNext
        );
    }

    public CursorPageResponse<PostCardResponse> searchPosts(
            CustomUserPrincipal principal,
            String query,
            String cursor,
            int size
    ) {
        UUID viewerId = requireViewerId(principal);
        String keyword = normalizeQuery(query);
        int pageSize = clampSize(size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        List<Post> loaded = searchQueryRepository.findActivePosts(
                keyword,
                PostSearchCursor.parse(cursor),
                pageSize + 1
        );
        boolean hasNext = loaded.size() > pageSize;
        List<Post> page = hasNext ? loaded.subList(0, pageSize) : loaded;
        String nextCursor = hasNext && !page.isEmpty()
                ? PostSearchCursor.encode(page.getLast().getCreatedAt(), page.getLast().getId())
                : null;

        return new CursorPageResponse<>(
                postResponseAssembler.toPostCards(page, viewerId),
                nextCursor,
                hasNext
        );
    }

    public List<HashtagSummaryResponse> searchHashtags(
            CustomUserPrincipal principal,
            String query,
            int size
    ) {
        requireViewerId(principal);
        String keyword = normalizeQuery(query);
        int resultSize = clampSize(size, DEFAULT_HASHTAG_SIZE, MAX_HASHTAG_SIZE);
        return findHashtags(keyword, resultSize).stream()
                .map(this::toHashtagResponse)
                .toList();
    }

    private SearchSectionResponse<SimpleUserProfileResponse> accountSection(
            List<User> loaded,
            int size
    ) {
        boolean hasMore = loaded.size() > size;
        List<User> page = hasMore ? loaded.subList(0, size) : loaded;
        return new SearchSectionResponse<>(
                page.stream().map(SimpleUserProfileResponse::from).toList(),
                hasMore
        );
    }

    private SearchSectionResponse<PostCardResponse> postSection(
            List<Post> loaded,
            UUID viewerId,
            int size
    ) {
        boolean hasMore = loaded.size() > size;
        List<Post> page = hasMore ? loaded.subList(0, size) : loaded;
        return new SearchSectionResponse<>(
                postResponseAssembler.toPostCards(page, viewerId),
                hasMore
        );
    }

    private SearchSectionResponse<HashtagSummaryResponse> hashtagSection(
            List<HashtagSummary> loaded,
            int size
    ) {
        boolean hasMore = loaded.size() > size;
        List<HashtagSummary> page = hasMore ? loaded.subList(0, size) : loaded;
        return new SearchSectionResponse<>(
                page.stream().map(this::toHashtagResponse).toList(),
                hasMore
        );
    }

    private List<HashtagSummary> findHashtags(String keyword, int size) {
        return hashtagParser.normalizeLookup(keyword)
                .map(normalized -> hashtagQueryRepository.findActiveSuggestions(normalized, size))
                .orElseGet(List::of);
    }

    private HashtagSummaryResponse toHashtagResponse(HashtagSummary summary) {
        return new HashtagSummaryResponse(
                summary.hashtagId(),
                summary.displayName(),
                summary.postCount()
        );
    }

    private UUID requireViewerId(CustomUserPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        return userRepository.findByIdAndDeletedAtIsNull(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found."
                ))
                .getId();
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            throw invalidQuery();
        }
        String normalized = query.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > MAX_QUERY_LENGTH) {
            throw invalidQuery();
        }
        return normalized;
    }

    private ResponseStatusException invalidQuery() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Search query must contain 1 to 100 characters."
        );
    }

    private int clampSize(int requested, int fallback, int maximum) {
        if (requested <= 0) {
            return fallback;
        }
        return Math.min(requested, maximum);
    }
}
