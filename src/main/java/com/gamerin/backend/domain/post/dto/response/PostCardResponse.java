package com.gamerin.backend.domain.post.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.gamerin.backend.domain.repost.dto.response.ReposterInfoResponse;

public record PostCardResponse(
                UUID postId,
                String author,
                String authorHandle,
                String authorProfileImageUrl,
                boolean authorVerifiedBadge,
                boolean authorMembershipBadge,
                String content,
                List<PostMediaResponse> media,
                long likes,
                long comments,
                long shares,
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
                        String author,
                        String authorHandle,
                        String authorProfileImageUrl,
                        boolean authorVerifiedBadge,
                        boolean authorMembershipBadge,
                        String content,
                        List<PostMediaResponse> media,
                        long likes,
                        long comments,
                        long shares,
                        boolean isReposted,
                        long repostCount,
                        ReposterInfoResponse reposterInfo,
                        boolean likedByMe,
                        boolean bookmarkedByMe,
                        boolean mine,
                        OffsetDateTime createdAt) {
                this(
                                postId,
                                author,
                                authorHandle,
                                authorProfileImageUrl,
                                authorVerifiedBadge,
                                authorMembershipBadge,
                                content,
                                media,
                                likes,
                                comments,
                                shares,
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