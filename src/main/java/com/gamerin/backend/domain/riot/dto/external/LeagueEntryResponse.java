package com.gamerin.backend.domain.riot.dto.external;


/*
소환사의 랭크 게임 정보(티어, 승리 횟수, 패배 횟수 등)를 받아올 때 쓰입니다.
*/
public record LeagueEntryResponse(
        String leagueId,
        String queueType,
        String tier,
        String rank,
        String puuid,
        int leaguePoints,
        int wins,
        int losses
) {
}