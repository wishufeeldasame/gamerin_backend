package com.gamerin.backend.domain.post.dto.request;

import com.gamerin.backend.domain.post.entity.PostMediaType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePostMediaRequest(
        PostMediaType mediaType,
        @NotBlank(message = "미디어 URL은 필수입니다.")
        @Size(max = 2048, message = "미디어 URL은 2048자를 초과할 수 없습니다.")
        String mediaUrl,
        @Size(max = 2048, message = "썸네일 URL은 2048자를 초과할 수 없습니다.")
        String thumbnailUrl,
        Integer sortOrder,
        Integer durationSeconds
) {
}
