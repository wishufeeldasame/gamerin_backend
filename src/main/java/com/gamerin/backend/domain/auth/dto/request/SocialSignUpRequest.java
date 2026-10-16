package com.gamerin.backend.domain.auth.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SocialSignUpRequest(
        @NotBlank(message = "임시 가입 토큰이 필요합니다.") String signupToken,
        @NotBlank(message = "핸들을 입력해주세요.") @Size(min = 3, max = 20) @Pattern(regexp = "^[a-z0-9_]{3,20}$", message = "핸들은 영문 소문자, 숫자, 밑줄(_)만 사용할 수 있습니다.") String handle,
        @NotBlank(message = "닉네임을 입력해주세요.") @Size(min = 2, max = 20) String nickname,
        @AssertTrue Boolean agreedToTerms,
        @AssertTrue Boolean agreedToPrivacy
        
) {
}