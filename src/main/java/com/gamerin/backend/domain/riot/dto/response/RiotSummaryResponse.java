package com.gamerin.backend.domain.riot.dto.response;


/*
게임별 승률, KDA, 티어 정보를 프론트엔드로 응답하는 DTO입니다.
*/
public record RiotSummaryResponse(
        String gameName,
        String tierLabel,
        double kda,
        int winRate,
        int games,
        boolean connected
) {
}