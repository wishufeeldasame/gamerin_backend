package com.gamerin.backend.domain.post.dto.request;

import com.gamerin.backend.domain.post.entity.ShareTarget;

public record CreateShareRequest(
        ShareTarget target
) {
    public ShareTarget normalizedTarget() {
        return target != null ? target : ShareTarget.COPY_LINK;
    }
}
