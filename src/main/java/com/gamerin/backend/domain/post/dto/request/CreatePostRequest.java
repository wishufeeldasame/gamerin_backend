package com.gamerin.backend.domain.post.dto.request;

import jakarta.validation.constraints.Size;

public record CreatePostRequest(
        @Size(max = 1000, message = "Post content must be 1000 characters or fewer.")
        String content
) {
}
