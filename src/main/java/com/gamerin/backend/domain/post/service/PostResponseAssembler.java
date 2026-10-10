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
import com.gamerin.backend.domain.post.dto.response.PostCardResponse;
import com.gamerin.backend.domain.post.dto.response.PostDetailResponse;
import com.gamerin.backend.domain.post.dto.response.PostMediaResponse;
import com.gamerin.backend.domain.post.dto.response.ProfileMediaItemResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.repository.PostBookmarkRepository;
import com.gamerin.backend.domain.post.repository.PostLikeRepository;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.repost.dto.response.ReposterInfoResponse;
import com.gamerin.backend.domain.repost.model.PostTimelineItem;
import com.gamerin.backend.domain.repost.model.RepostMetrics;
import com.gamerin.backend.domain.repost.repository.RepostQueryRepository;
import com.gamerin.backend.domain.user.entity.UserProfile;

@Component
public class PostResponseAssembler {

    private final PostMediaRepository postMediaRepository;
    private final PostLikeRepository postLikeRepository;
    private final PostBookmarkRepository postBookmarkRepository;
    private final RepostQueryRepository repostQueryRepository;

    public PostResponseAssembler(
            PostMediaRepository postMediaRepository,
            PostLikeRepository postLikeRepository,
            PostBookmarkRepository postBookmarkRepository,
            RepostQueryRepository repostQueryRepository
    ) {
        this.postMediaRepository = postMediaRepository;
        this.postLikeRepository = postLikeRepository;
        this.postBookmarkRepository = postBookmarkRepository;
        this.repostQueryRepository = repostQueryRepository;
    }

    public List<PostCardResponse> toPostCards(List<Post> posts, UUID viewerId) {
        return toPostCards(posts, viewerId, Map.of());
    }

    public List<PostCardResponse> toTimelinePostCards(
            List<PostTimelineItem> timelineItems,
            UUID viewerId
    ) {
        Map<UUID, ReposterInfoResponse> reposterInfoByPostId = new HashMap<>();
        List<Post> posts = new ArrayList<>();
        for (PostTimelineItem item : timelineItems) {
            posts.add(item.post());
            if (item.reposterInfo() != null) {
                reposterInfoByPostId.put(item.post().getId(), item.reposterInfo());
            }
        }
        return toPostCards(posts, viewerId, reposterInfoByPostId);
    }

    private List<PostCardResponse> toPostCards(
            List<Post> posts,
            UUID viewerId,
            Map<UUID, ReposterInfoResponse> reposterInfoByPostId
    ) {
        if (posts.isEmpty()) {
            return List.of();
        }

        List<UUID> postIds = extractPostIds(posts);
        Map<UUID, List<PostMediaResponse>> mediaMap = buildMediaMap(postIds);
        Set<UUID> likedPostIds = buildLikedPostIds(viewerId, postIds);
        Set<UUID> bookmarkedPostIds = buildBookmarkedPostIds(viewerId, postIds);
        Map<UUID, RepostMetrics> repostMetrics = repostQueryRepository.findMetrics(viewerId, postIds);

        List<PostCardResponse> responses = new ArrayList<>();
        for (Post post : posts) {
            responses.add(toPostCard(
                    post,
                    mediaMap.getOrDefault(post.getId(), List.of()),
                    likedPostIds.contains(post.getId()),
                    bookmarkedPostIds.contains(post.getId()),
                    repostMetrics.getOrDefault(post.getId(), new RepostMetrics(0L, false)),
                    reposterInfoByPostId.get(post.getId()),
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
        boolean bookmarkedByMe = viewerId != null && postBookmarkRepository.existsByPostIdAndUserId(post.getId(), viewerId);
        RepostMetrics repostMetrics = repostQueryRepository.findMetrics(viewerId, List.of(post.getId()))
                .getOrDefault(post.getId(), new RepostMetrics(0L, false));
        boolean mine = viewerId != null && post.getAuthor().getId().equals(viewerId);
        UserProfile profile = post.getAuthor().getProfile();

        return new PostDetailResponse(
                post.getId(),
                post.getAuthor().getNickname(),
                post.getAuthor().getHandle(),
                profile != null ? profile.getProfileImageUrl() : null,
                profile != null && profile.isVerifiedBadge(),
                post.getContent(),
                media,
                post.getLikeCount(),
                post.getCommentCount(),
                post.getShareCount(),
                repostMetrics.repostedByViewer(),
                repostMetrics.repostCount(),
                null,
                likedByMe,
                bookmarkedByMe,
                mine,
                post.getCreatedAt()
        );
    }

    public CommentResponse toCommentResponse(PostComment comment, UUID viewerId) {
        UserProfile profile = comment.getAuthor().getProfile();
        return new CommentResponse(
                comment.getId(),
                comment.getAuthor().getNickname(),
                comment.getAuthor().getHandle(),
                profile != null ? profile.getProfileImageUrl() : null,
                profile != null && profile.isVerifiedBadge(),
                comment.getContent(),
                comment.getCreatedAt(),
                viewerId != null && comment.getAuthor().getId().equals(viewerId)
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
            boolean likedByMe,
            boolean bookmarkedByMe,
            RepostMetrics repostMetrics,
            ReposterInfoResponse reposterInfo,
            UUID viewerId
    ) {
        UserProfile profile = post.getAuthor().getProfile();
        return new PostCardResponse(
                post.getId(),
                post.getAuthor().getNickname(),
                post.getAuthor().getHandle(),
                profile != null ? profile.getProfileImageUrl() : null,
                profile != null && profile.isVerifiedBadge(),
                post.getContent(),
                media,
                post.getLikeCount(),
                post.getCommentCount(),
                post.getShareCount(),
                repostMetrics.repostedByViewer(),
                repostMetrics.repostCount(),
                reposterInfo,
                likedByMe,
                bookmarkedByMe,
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

    private Set<UUID> buildLikedPostIds(UUID viewerId, Collection<UUID> postIds) {
        if (viewerId == null || postIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(postLikeRepository.findLikedPostIds(viewerId, postIds));
    }

    private Set<UUID> buildBookmarkedPostIds(UUID viewerId, Collection<UUID> postIds) {
        if (viewerId == null || postIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(postBookmarkRepository.findBookmarkedPostIds(viewerId, postIds));
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
                media.getSortOrder()
        );
    }
}
