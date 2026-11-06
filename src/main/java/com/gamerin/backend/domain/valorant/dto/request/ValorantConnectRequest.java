package com.gamerin.backend.domain.valorant.dto.request;

import com.gamerin.backend.domain.valorant.model.ValorantRegion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ValorantConnectRequest(
        @NotBlank(message = "Riot ID는 필수입니다. (예: 닉네임#TAG)")
        @Size(max = 30, message = "Riot ID는 30자 이하여야 합니다.")
        String riotId,
        @NotNull(message = "리전은 필수입니다. (KR, AP)")
        ValorantRegion region
) {
}
