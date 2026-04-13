package com.gamerin.backend.domain.auth.dto.response;

import java.time.OffsetDateTime;

public record FindIdResponse(
        String maskedHandle,
        OffsetDateTime createdAt // 가입일 필드 추가함
) {
}
