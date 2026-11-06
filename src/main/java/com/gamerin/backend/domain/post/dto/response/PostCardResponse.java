package com.gamerin.backend.domain.post.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.gamerin.backend.domain.repost.dto.response.ReposterInfoResponse;
import com.gamerin.backend.domain.user.dto.response.RepresentativeTierResponse;

public record PostCardResponse(
                UUID postId,
                Long postNumber,
                String author,
                String authorHandle,
                String authorProfileImageUrl,
                boolean authorVerifiedBadge,
                boolean authorMembershipBadge,
                RepresentativeTierResponse authorRepresentativeTier,
                String content,
                List<PostMediaResponse> media,
                long likes,
                long comments,
                long shares,
                long viewCount,
                boolean isReposted,
                long repostCount,
                ReposterInfoResponse reposterInfo,
                boolean likedByMe,
                boolean bookmarkedByMe,
                boolean mine,
                OffsetDateTime createdAt,
                /** KP-C3 (#92): 작성자 본인 조회 시 수정 가능 마감 시각 (타인 조회 시 null) */
                OffsetDateTime editableUntil,
                /** KP-C3 (#92): 작성자 본인 조회 시 수정 횟수 0~1 (타인 조회 시 null) */
                Integer editCount) {

        public PostCardResponse(
                        UUID postId,
                Long postNumber,
                        String author,
                        String authorHandle,
                        String authorProfileImageUrl,
                        boolean authorVerifiedBadge,
                        boolean authorMembershipBadge,
                        RepresentativeTierResponse authorRepresentativeTier,
                        String content,
                        List<PostMediaResponse> media,
                        long likes,
                        long comments,
                        long shares,
                        long viewCount,
                        boolean isReposted,
                        long repostCount,
                        ReposterInfoResponse reposterInfo,
                        boolean likedByMe,
                        boolean bookmarkedByMe,
                        boolean mine,
                        OffsetDateTime createdAt) {
                this(
                                postId,
                                postNumber,
                                author,
                                authorHandle,
                                authorProfileImageUrl,
                                authorVerifiedBadge,
                                authorMembershipBadge,
                                authorRepresentativeTier,
                                content,
                                media,
                                likes,
                                comments,
                                shares,
                                viewCount,
                                isReposted,
                                repostCount,
                                reposterInfo,
                                likedByMe,
                                bookmarkedByMe,
                                mine,
                                createdAt,
                                null,
                                null);
        }
}