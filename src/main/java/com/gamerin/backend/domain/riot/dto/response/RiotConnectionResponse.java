package com.gamerin.backend.domain.riot.dto.response;

/*
Riot 계정 연동 성공 시 결과를 응답하는 DTO입니다.
*/
public record RiotConnectionResponse(
    boolean connected,
    String riotId
) {
}
