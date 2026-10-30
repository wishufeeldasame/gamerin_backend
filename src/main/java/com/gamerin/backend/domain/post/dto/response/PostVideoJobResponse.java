package com.gamerin.backend.domain.post.dto.response;

import java.util.UUID;

/**
 * 영상 게시물 처리 작업 상태. 처리가 끝나면(COMPLETED) 만들어진 게시물을 함께 내려준다.
 */
public record PostVideoJobResponse(
        UUID jobId,
        String status,
        String errorMessage,
        PostDetailResponse post
) {
}
