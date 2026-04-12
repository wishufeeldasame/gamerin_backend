package com.gamerin.backend.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SocialSignUpRequest(
        @NotBlank(message = "임시 가입 토큰이 필요합니다.") String registerToken,
        @NotBlank(message = "핸들을 입력해주세요.") @Pattern(regexp = "^[a-z0-9_]{3,20}$", message = "핸들은 영문 소문자, 숫자, 언더바(_)만 사용 가능합니다.") String handle,
        @NotBlank(message = "닉네임을 입력해주세요.") String nickname
) {
}