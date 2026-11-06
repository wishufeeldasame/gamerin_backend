package com.gamerin.backend.domain.post.dto.response;

/** counted: 이번 요청으로 조회수가 실제 증가한 게시물 수(24시간 중복·삭제·숨김 글 제외). */
public record RecordPostViewsResponse(int counted) {
}
