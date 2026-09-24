package com.gamerin.backend.domain.hashtag.service;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.hashtag.dto.response.HashtagSummaryResponse;
import com.gamerin.backend.domain.hashtag.model.HashtagPostCursor;
import com.gamerin.backend.domain.hashtag.repository.HashtagQueryRepository;
import com.gamerin.backend.domain.post.dto.response.PostCardResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.service.PostResponseAssembler;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@Service
@Transactional(readOnly = true)
public class HashtagQueryService {

    private static final int DEFAULT_POST_PAGE_SIZE = 20;
    private static final int MAX_POST_PAGE_SIZE = 50;
    private static final int DEFAULT_SUGGESTION_SIZE = 10;
    private static final int MAX_SUGGESTION_SIZE = 20;

    private final HashtagParser hashtagParser;
    private final HashtagQueryRepository hashtagQueryRepository;
    private final PostResponseAssembler postResponseAssembler;
    private final UserRepository userRepository;

    public HashtagQueryService(
            HashtagParser hashtagParser,
            HashtagQueryRepository hashtagQueryRepository,
            PostResponseAssembler postResponseAssembler,
            UserRepository userRepository
    ) {
        this.hashtagParser = hashtagParser;
        this.hashtagQueryRepository = hashtagQueryRepository;
        this.postResponseAssembler = postResponseAssembler;
        this.userRepository = userRepository;
    }

    public List<HashtagSummaryResponse> autocomplete(
            CustomUserPrincipal principal,
            String query,
            int size
    ) {
        requireViewerId(principal);
        String normalizedPrefix = normalizeLookup(query);
        int pageSize = clampSize(size, DEFAULT_SUGGESTION_SIZE, MAX_SUGGESTION_SIZE);

        return hashtagQueryRepository.findActiveSuggestions(normalizedPrefix, pageSize)
                .stream()
                .map(summary -> new HashtagSummaryResponse(
                        summary.hashtagId(),
                        summary.displayName(),
                        summary.postCount()
                ))
                .toList();
    }

    public CursorPageResponse<PostCardResponse> getPosts(
            CustomUserPrincipal principal,
            String name,
            String cursor,
            int size
    ) {
        UUID viewerId = requireViewerId(principal);
        String normalizedName = normalizeLookup(name);
        int pageSize = clampSize(size, DEFAULT_POST_PAGE_SIZE, MAX_POST_PAGE_SIZE);
        HashtagPostCursor parsedCursor = HashtagPostCursor.parse(cursor);
        List<Post> loadedPosts = hashtagQueryRepository.findActivePosts(
                normalizedName,
                parsedCursor,
                pageSize + 1
        );
        boolean hasNext = loadedPosts.size() > pageSize;
        List<Post> pagePosts = hasNext ? loadedPosts.subList(0, pageSize) : loadedPosts;
        String nextCursor = hasNext && !pagePosts.isEmpty()
                ? HashtagPostCursor.encode(
                        pagePosts.getLast().getCreatedAt(),
                        pagePosts.getLast().getId()
                )
                : null;

        return new CursorPageResponse<>(
                postResponseAssembler.toPostCards(pagePosts, viewerId),
                nextCursor,
                hasNext
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

    private String normalizeLookup(String value) {
        return hashtagParser.normalizeLookup(value)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Hashtag must contain 1 to 50 letters, numbers, or underscores."
                ));
    }

    private int clampSize(int requested, int fallback, int maximum) {
        if (requested <= 0) {
            return fallback;
        }
        return Math.min(requested, maximum);
    }
}
