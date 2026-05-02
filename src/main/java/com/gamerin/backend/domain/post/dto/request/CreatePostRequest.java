package com.gamerin.backend.domain.post.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

public record CreatePostRequest(
        @Size(max = 1000, message = "게시글 내용은 1000자를 초과할 수 없습니다.")
        String content,
        @Size(max = 50, message = "게임 이름은 50자를 초과할 수 없습니다.")
        String gameName,
        @Valid
        List<CreatePostMediaRequest> media
) {
}
