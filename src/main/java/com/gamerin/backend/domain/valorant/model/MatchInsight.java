package com.gamerin.backend.domain.valorant.model;

/**
 * 경기 1건에서 대상 플레이어의 분석 근거 합계. 주간 집계에서 경기끼리 더해 쓴다.
 * 킬·데스는 서로 다른 팀 간 처치만 센다(자살·팀킬 제외). 응답에 값이 없으면 0이 아니라 null이다.
 *
 * @param damageDealt 경기 피해량 합. 없으면 null
 * @param firstKillRoundWins 첫 킬을 낸 라운드 중 승리 수
 * @param firstDeathRoundWins 첫 데스를 당한 라운드 중 승리 수
 * @param attack 공격 진영 라운드. winning_team_role 이 Attacker/Defender 가 아니면 그 라운드는 빠진다
 * @param postPlantAttack 우리 팀이 설치한 라운드. 킬·데스는 설치 시각 이후만 센다
 * @param postPlantDefense 상대가 설치한 라운드
 * @param tradedDeaths 내 데스 후 5초 안에 아군이 내 처치자를 처치한 횟수
 * @param tradeKills 아군을 처치한 적을 5초 안에 내가 처치한 횟수
 * @param economyBehind 팀 평균 loadout_value 격차(우리−상대) ≤ −1000 라운드
 * @param economyAhead 격차 ≥ +1000 라운드. 그 사이는 economyEven
 * @param abilityCasts 경기 단위 스킬 사용 수. 없으면 null
 * @param allyAfk 나를 뺀 아군에 was_afk 라운드가 있었는지
 * @param enemyAfk 상대에 was_afk 라운드가 있었는지
 */
public record MatchInsight(
        Integer damageDealt,
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
        RoundGroup economyAhead,
        AbilityCasts abilityCasts,
        boolean allyAfk,
        boolean enemyAfk,
        int myAfkRounds
) {

    /** 라운드 묶음의 라운드·승리 수와 그 안의 내 킬·데스. */
    public record RoundGroup(int rounds, int wins, int kills, int deaths) {
        public static final RoundGroup EMPTY = new RoundGroup(0, 0, 0, 0);

        public RoundGroup plus(RoundGroup other) {
            return other == null ? this : new RoundGroup(rounds + other.rounds, wins + other.wins,
                    kills + other.kills, deaths + other.deaths);
        }
    }

    /** grenade/ability1/ability2/ultimate = C/Q/E/X. */
    public record AbilityCasts(int grenade, int ability1, int ability2, int ultimate) {
    }
}
