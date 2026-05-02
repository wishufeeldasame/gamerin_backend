package com.gamerin.backend.domain.post.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

public record CreatePostRequest(
        @Size(max = 1000, message = "Post content must be 1000 characters or fewer.")
        String content,
        @Size(max = 50, message = "Game name must be 50 characters or fewer.")
        String gameName,
        @Valid
        List<CreatePostMediaRequest> media,
        @Valid
        CreateExternalLinkRequest externalLink
) {
}
