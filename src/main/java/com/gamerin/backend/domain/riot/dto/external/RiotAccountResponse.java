package com.gamerin.backend.domain.riot.dto.external;

/*
Riot ID를 이용해 사용자의 고유 식별값인 PUUID를 조회할 때 쓰입니다.
*/
public record RiotAccountResponse(
        String puuid,
        String gameName,
        String tagLine
) {
}