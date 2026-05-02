package com.gamerin.backend.domain.post.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.gamerin.backend.domain.post.dto.response.CommentResponse;
import com.gamerin.backend.domain.post.dto.response.ExternalLinkCardResponse;
import com.gamerin.backend.domain.post.dto.response.PostCardResponse;
import com.gamerin.backend.domain.post.dto.response.PostDetailResponse;
import com.gamerin.backend.domain.post.dto.response.PostMediaResponse;
import com.gamerin.backend.domain.post.dto.response.ProfileMediaItemResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.entity.PostExternalLink;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.repository.PostExternalLinkRepository;
import com.gamerin.backend.domain.post.repository.PostLikeRepository;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.user.entity.UserProfile;

@Component
public class PostResponseAssembler {

    private final PostMediaRepository postMediaRepository;
    private final PostExternalLinkRepository postExternalLinkRepository;
    private final PostLikeRepository postLikeRepository;

    public PostResponseAssembler(
            PostMediaRepository postMediaRepository,
            PostExternalLinkRepository postExternalLinkRepository,
            PostLikeRepository postLikeRepository
    ) {
        this.postMediaRepository = postMediaRepository;
        this.postExternalLinkRepository = postExternalLinkRepository;
        this.postLikeRepository = postLikeRepository;
    }

    public List<PostCardResponse> toPostCards(List<Post> posts, UUID viewerId) {
        if (posts.isEmpty()) {
            return List.of();
        }

        List<UUID> postIds = extractPostIds(posts);
        Map<UUID, List<PostMediaResponse>> mediaMap = buildMediaMap(postIds);
        Map<UUID, ExternalLinkCardResponse> externalLinkMap = buildExternalLinkMap(postIds);
        Set<UUID> likedPostIds = buildLikedPostIds(viewerId, postIds);

        List<PostCardResponse> responses = new ArrayList<>();
        for (Post post : posts) {
            responses.add(toPostCard(
                    post,
                    mediaMap.getOrDefault(post.getId(), List.of()),
                    externalLinkMap.get(post.getId()),
                    likedPostIds.contains(post.getId()),
                    viewerId
            ));
        }
        return responses;
    }

    public PostDetailResponse toPostDetail(Post post, UUID viewerId) {
        List<PostMediaResponse> media = postMediaRepository.findActiveByPostId(post.getId())
                .stream()
                .map(this::toPostMediaResponse)
                .toList();

        boolean likedByMe = viewerId != null && postLikeRepository.existsByPostIdAndUserId(post.getId(), viewerId);
        boolean mine = viewerId != null && post.getAuthor().getId().equals(viewerId);
        UserProfile profile = post.getAuthor().getProfile();
        ExternalLinkCardResponse externalLink = postExternalLinkRepository.findByPostId(post.getId())
                .map(this::toExternalLinkCardResponse)
                .orElse(null);

        return new PostDetailResponse(
                post.getId(),
                post.getAuthor().getNickname(),
                post.getAuthor().getHandle(),
                profile != null ? profile.getProfileImageUrl() : null,
                profile != null && profile.isVerifiedBadge(),
                post.getGameName(),
                post.getContent(),
                media,
                externalLink,
                post.getLikeCount(),
                post.getCommentCount(),
                post.getShareCount(),
                likedByMe,
                mine,
                post.getCreatedAt()
        );
    }

    public CommentResponse toCommentResponse(PostComment comment) {
        UserProfile profile = comment.getAuthor().getProfile();
        return new CommentResponse(
                comment.getId(),
                comment.getAuthor().getNickname(),
                comment.getAuthor().getHandle(),
                profile != null ? profile.getProfileImageUrl() : null,
                profile != null && profile.isVerifiedBadge(),
                comment.getContent(),
                comment.getCreatedAt()
        );
    }

    public List<ProfileMediaItemResponse> toProfileMediaItems(List<PostMedia> mediaList) {
        return mediaList.stream()
                .map(media -> new ProfileMediaItemResponse(
                        media.getId(),
                        media.getPost().getId(),
                        media.getPost().getAuthor().getHandle(),
                        media.getMediaType(),
                        media.getMediaUrl(),
                        media.getThumbnailUrl(),
                        media.getPost().getCreatedAt()
                ))
                .toList();
    }

    private PostCardResponse toPostCard(
            Post post,
            List<PostMediaResponse> media,
            ExternalLinkCardResponse externalLink,
            boolean likedByMe,
            UUID viewerId
    ) {
        UserProfile profile = post.getAuthor().getProfile();
        return new PostCardResponse(
                post.getId(),
                post.getAuthor().getNickname(),
                post.getAuthor().getHandle(),
                profile != null ? profile.getProfileImageUrl() : null,
                profile != null && profile.isVerifiedBadge(),
                post.getGameName(),
                post.getContent(),
                media,
                externalLink,
                post.getLikeCount(),
                post.getCommentCount(),
                post.getShareCount(),
                likedByMe,
                viewerId != null && post.getAuthor().getId().equals(viewerId),
                post.getCreatedAt()
        );
    }

    private Map<UUID, List<PostMediaResponse>> buildMediaMap(Collection<UUID> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, List<PostMediaResponse>> mediaMap = new HashMap<>();
        for (PostMedia media : postMediaRepository.findActiveByPostIds(postIds)) {
            mediaMap.computeIfAbsent(media.getPost().getId(), ignored -> new ArrayList<>())
                    .add(toPostMediaResponse(media));
        }
        return mediaMap;
    }

    private Map<UUID, ExternalLinkCardResponse> buildExternalLinkMap(Collection<UUID> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, ExternalLinkCardResponse> linkMap = new HashMap<>();
        for (PostExternalLink externalLink : postExternalLinkRepository.findByPostIds(postIds)) {
            linkMap.put(externalLink.getPost().getId(), toExternalLinkCardResponse(externalLink));
        }
        return linkMap;
    }

    private Set<UUID> buildLikedPostIds(UUID viewerId, Collection<UUID> postIds) {
        if (viewerId == null || postIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(postLikeRepository.findLikedPostIds(viewerId, postIds));
    }

    private List<UUID> extractPostIds(List<Post> posts) {
        return posts.stream()
                .map(Post::getId)
                .toList();
    }

    private PostMediaResponse toPostMediaResponse(PostMedia media) {
        return new PostMediaResponse(
                media.getId(),
                media.getMediaType(),
                media.getMediaUrl(),
                media.getThumbnailUrl(),
                media.getSortOrder(),
                media.getDurationSeconds()
        );
    }

    private ExternalLinkCardResponse toExternalLinkCardResponse(PostExternalLink externalLink) {
        return new ExternalLinkCardResponse(
                externalLink.getOriginalUrl(),
                externalLink.getHost(),
                externalLink.getTitle(),
                externalLink.getDescription(),
                externalLink.getThumbnailUrl()
        );
    }
}
