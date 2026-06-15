package com.gamerin.backend.domain.user.dto.response;

import com.gamerin.backend.domain.user.dto.request.ProfileImageTarget;

public record ProfileImageUploadResponse(
        ProfileImageTarget target,
        String imageUrl,
        long sizeBytes
) {
}
