package com.gamerin.backend.domain.weeklyreport.model;

import java.util.List;

import com.gamerin.backend.domain.valorant.model.MatchInsight.RoundGroup;

/**
 * AI 입력용 주간 집계. 대상 주(current)·비교 주(previous) 각각 경기가 없으면 null이다.
 * 닉네임·PUUID·경기 ID·날짜는 담지 않는다. DB에 저장하지 않는다.
 */
public record WeeklyInsight(Week current, Week previous) {

    /**
     * @param wins 승리 경기 묶음. 없으면 null
     * @param losses 패배 경기 묶음. 없으면 null
     * @param afkMatches 아군·상대·나 중 AFK 라운드가 있었던 경기 수(근거 있는 경기 기준)
     */
    public record Week(
            Stats overall,
            List<Group> byMap,
            List<Group> byAgent,
            List<Group> byMapAgent,
            Stats wins,
            Stats losses,
            List<AgentAbility> abilities,
            int afkMatches
    ) {
    }

    /** label은 맵·요원 이름 또는 "맵 × 요원". 표본 수는 stats.matches. */
    public record Group(String label, Stats stats) {
    }

    /**
     * 기본 지표는 전체 경기, evidence는 insight 가 있는 경기(insightMatches)만으로 합산한다.
     *
     * @param evidence insightMatches 가 0이면 null
     */
    public record Stats(
            int matches,
            int wins,
            int rounds,
            int kills,
            int deaths,
            Double kd,
            Double acs,
            Double headshotPercent,
            int insightMatches,
            Evidence evidence
    ) {
    }

    /** @param adr 피해량이 있는 경기의 피해 합 ÷ 라운드 합. 그런 경기가 없으면 null */
    public record Evidence(
            Double adr,
            int firstKills,
            int firstDeaths,
            int firstKillRoundWins,
            int firstDeathRoundWins,
            RoundGroup attack,
            RoundGroup defense,
            RoundGroup postPlantAttack,
            RoundGroup postPlantDefense,
            int tradedDeaths,
            int tradeKills,
            RoundGroup economyBehind,
            RoundGroup economyEven,
            RoundGroup economyAhead
    ) {
    }

    /** 스킬 사용량이 있는 경기의 라운드당 사용 수(C/Q/E/X). */
    public record AgentAbility(String agent, int matches, int rounds, double grenadePerRound,
            double ability1PerRound, double ability2PerRound, double ultimatePerRound) {
    }
}
