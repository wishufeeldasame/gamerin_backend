package com.gamerin.backend.domain.auth.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignUpRequest(
        @NotBlank
        @Size(min = 4, max = 20)
        @Pattern(regexp = "^[a-z0-9._]+$", message = "아이디는 영문 소문자, 숫자, 점(.), 밑줄(_)만 사용할 수 있습니다.")
        String handle,

        @NotBlank
        @Size(min = 2, max = 20)
        String nickname,

        @NotBlank
        @jakarta.validation.constraints.Email
        String email,

        @NotBlank
        @Size(min = 8, max = 20)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).+$", message = "비밀번호는 영문, 숫자, 특수문자를 모두 포함해야 합니다.")
        String password,

        @NotBlank
        String passwordConfirm,

        @AssertTrue(message = "이용약관 동의가 필요합니다.")
        Boolean agreedToTerms,

        @AssertTrue(message = "개인정보 처리방침 동의가 필요합니다.")
        Boolean agreedToPrivacy
) {
}
