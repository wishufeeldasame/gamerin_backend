package com.gamerin.backend.domain.riot.dto.request;

import jakarta.validation.constraints.NotBlank;

/*
Riot ID(예:  hide on bush#KR1 ) 입력을 받기 위한 DTO입니다.
#  기호를 기준으로 닉네임과 태그라인을 분리하여 사용하게 됩니다.
*/
public record RiotConnectRequest(
    @NotBlank(message = "Riot ID는 필수입니다. (예: 닉네임#TAG)")
    String riotId
) {
}