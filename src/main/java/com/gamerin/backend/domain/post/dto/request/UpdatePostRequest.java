package com.gamerin.backend.domain.post.dto.request;

import jakarta.validation.constraints.Size;

/**
 * KP-C3 (#92): 멤버십 게시물 본문 수정 요청 DTO.
 * 미디어 필드는 포함하지 않고 본문 텍스트만 수정한다.
 */
public record UpdatePostRequest(
        @Size(max = 1000, message = "Post content must be 1000 characters or fewer.") String content) {
}