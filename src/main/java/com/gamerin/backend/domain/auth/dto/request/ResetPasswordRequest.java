package com.gamerin.backend.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank
        String resetToken,

        @NotBlank
        @Size(min = 8, max = 20)
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).+$",
                message = "비밀번호는 영문, 숫자, 특수문자를 모두 포함해야 합니다."
        )
        String newPassword,

        @NotBlank
        String newPasswordConfirm
) {
}
