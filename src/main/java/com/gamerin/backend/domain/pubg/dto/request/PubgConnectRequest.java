package com.gamerin.backend.domain.pubg.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PubgConnectRequest(
        @NotBlank(message = "PUBG 닉네임은 필수입니다.")
        @Size(min = 4, max = 16, message = "PUBG 닉네임은 4자 이상 16자 이하여야 합니다.")
        @Pattern(
                regexp = "^[A-Za-z0-9_-]{4,16}$",
                message = "PUBG 닉네임은 영문, 숫자, 하이픈(-), 언더바(_)만 사용할 수 있습니다."
        )
        String playerName
) {
}
