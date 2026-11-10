package com.gamerin.backend.domain.weeklyreport.model;

import java.util.List;

/**
 * 한 주의 경쟁전 집계. winRate·headshotRate는 소수 1자리 %, kd는 소수 2자리다.
 * 데스 0이면 kd, 사격 기록이 없으면 headshotRate, 라운드가 없으면 acs가 null이다.
 * tierLabel은 그 주 마지막 경기 시점의 한국어 티어다.
 */
public record WeekStats(
        int matches,
        int wins,
        double winRate,
        Double kd,
        Double headshotRate,
        Integer acs,
        String tierLabel,
        List<Breakdown> maps,
        List<Breakdown> agents
) {

    public record Breakdown(String name, int wins, int losses) {
    }
}
