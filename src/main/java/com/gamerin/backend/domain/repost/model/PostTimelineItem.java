package com.gamerin.backend.domain.repost.model;

import java.time.OffsetDateTime;

import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.repost.dto.response.ReposterInfoResponse;

public record PostTimelineItem(
        Post post,
        ReposterInfoResponse reposterInfo,
        OffsetDateTime activityAt
) {
}
