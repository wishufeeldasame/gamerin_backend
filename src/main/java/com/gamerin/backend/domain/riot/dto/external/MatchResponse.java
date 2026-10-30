package com.gamerin.backend.domain.riot.dto.external;

import java.util.List;

/*
KDA를 구하기 위해 매치 상세 기록 정보 중 킬, 데스, 어시스트 횟수를 받아올 때 쓰입니다.
*/

public record MatchResponse(
        MatchInfo info
) {
    public record MatchInfo(
            List<ParticipantDto> participants
    ) {
    }

    public record ParticipantDto(
            String puuid,
            int kills,
            int deaths,
            int assists
    ) {
    }
}
