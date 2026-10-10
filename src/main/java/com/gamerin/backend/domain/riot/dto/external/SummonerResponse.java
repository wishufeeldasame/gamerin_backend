package com.gamerin.backend.domain.riot.dto.external;

/*
LoL 소환사 ID(Summoner ID)를 조회할 때 쓰입니다.
*/
public record SummonerResponse(
        String id,
        String accountId,
        String puuid,
        String name,
        int profileIconId,
        long revisionDate,
        long summonerLevel
) {
}
