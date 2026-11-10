package com.gamerin.backend.domain.weeklyreport.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.valorant.model.ValorantMatchDetail;
import com.gamerin.backend.domain.valorant.model.ValorantMatchStats;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 발로란트 경쟁전 한 경기의 플레이어 기록. 회원이 아닌 계정도 담을 수 있게 puuid 기준이다. */
@Entity
@Table(name = "valorant_match_records", uniqueConstraints = @UniqueConstraint(
        name = "uk_valorant_match_records_puuid_match", columnNames = {"puuid", "match_id"}))
public class ValorantMatchRecord {

    @Id private UUID id;
    @Column(nullable = false, length = 100) private String puuid;
    @Column(name = "match_id", nullable = false, length = 64) private String matchId;
    @Column(name = "started_at", nullable = false) private OffsetDateTime startedAt;
    @Column(name = "map_name", length = 50) private String mapName;
    @Column(name = "agent_name", length = 50) private String agentName;
    @Column(name = "tier_id") private Integer tierId;
    @Column(name = "tier_label", length = 30) private String tierLabel;
    @Column(nullable = false) private int kills;
    @Column(nullable = false) private int deaths;
    @Column(nullable = false) private int assists;
    @Column(nullable = false) private int score;
    @Column(nullable = false) private int rounds;
    @Column(nullable = false) private int headshots;
    @Column(nullable = false) private int bodyshots;
    @Column(nullable = false) private int legshots;
    @Column(nullable = false) private boolean won;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;

    protected ValorantMatchRecord() {}

    public static ValorantMatchRecord of(String puuid, ValorantMatchDetail match) {
        ValorantMatchStats stats = match.stats();
        ValorantMatchRecord record = new ValorantMatchRecord();
        record.id = UUID.randomUUID();
        record.puuid = puuid;
        record.matchId = match.matchId();
        record.startedAt = match.startedAt().atOffset(java.time.ZoneOffset.UTC);
        record.mapName = match.map();
        record.agentName = match.agent();
        record.tierId = match.tierId();
        record.tierLabel = match.tierLabel();
        record.kills = stats.kills();
        record.deaths = stats.deaths();
        record.assists = stats.assists();
        record.score = stats.score();
        record.rounds = stats.rounds();
        record.headshots = stats.headshots();
        record.bodyshots = stats.bodyshots();
        record.legshots = stats.legshots();
        record.won = stats.won();
        record.createdAt = OffsetDateTime.now();
        return record;
    }

    public String getMatchId() { return matchId; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public String getMapName() { return mapName; }
    public String getAgentName() { return agentName; }
    public Integer getTierId() { return tierId; }
    public String getTierLabel() { return tierLabel; }
    public int getKills() { return kills; }
    public int getDeaths() { return deaths; }
    public int getScore() { return score; }
    public int getRounds() { return rounds; }
    public int getHeadshots() { return headshots; }
    public int getBodyshots() { return bodyshots; }
    public int getLegshots() { return legshots; }
    public boolean isWon() { return won; }
}
