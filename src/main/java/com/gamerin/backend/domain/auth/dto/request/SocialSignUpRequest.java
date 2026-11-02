package com.gamerin.backend.domain.auth.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SocialSignUpRequest(
        @NotBlank(message = "임시 가입 토큰이 필요합니다.") String signupToken,
        @NotBlank(message = "핸들을 입력해주세요.") @Size(min = 3, max = 20) @Pattern(regexp = "^[a-z0-9_]{3,20}$", message = "핸들은 영문 소문자, 숫자, 밑줄(_)만 사용할 수 있습니다.") String handle,
        @NotBlank(message = "닉네임을 입력해주세요.") @Size(min = 2, max = 20) String nickname,
        @NotNull(message = "이용약관 동의가 필요합니다.")
        @AssertTrue(message = "이용약관 동의가 필요합니다.")
        Boolean agreedToTerms,

        @NotNull(message = "개인정보 처리방침 동의가 필요합니다.")
        @AssertTrue(message = "개인정보 처리방침 동의가 필요합니다.")
        Boolean agreedToPrivacy,

        @NotNull(message = "만 14세 이상만 가입할 수 있습니다.")
        @AssertTrue(message = "만 14세 이상만 가입할 수 있습니다.")
        Boolean agreedToAge14,

        @NotBlank(message = "약관 버전이 필요합니다.")
        String termsVersion,

        @NotBlank(message = "개인정보 처리방침 버전이 필요합니다.")
        String privacyVersion
) {
}