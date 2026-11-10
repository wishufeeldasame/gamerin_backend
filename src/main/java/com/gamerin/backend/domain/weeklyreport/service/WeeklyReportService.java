package com.gamerin.backend.domain.weeklyreport.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.gamerin.backend.domain.game.model.GameType;
import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.valorant.client.HenrikValorantClient;
import com.gamerin.backend.domain.valorant.model.ValorantAccount;
import com.gamerin.backend.domain.valorant.model.ValorantMatchDetail;
import com.gamerin.backend.domain.valorant.model.ValorantMatchReference;
import com.gamerin.backend.domain.valorant.model.ValorantRegion;
import com.gamerin.backend.domain.valorant.service.ValorantService;
import com.gamerin.backend.domain.weeklyreport.client.WeeklyReportAiClient;
import com.gamerin.backend.domain.weeklyreport.dto.response.WeeklyGameReportResponse;
import com.gamerin.backend.domain.weeklyreport.entity.ValorantMatchRecord;
import com.gamerin.backend.domain.weeklyreport.entity.WeeklyGameReport;
import com.gamerin.backend.domain.weeklyreport.entity.WeeklyReportStatus;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyAnalysis;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyReportPeriod;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyReportCollectionIssue;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyStats;
import com.gamerin.backend.domain.weeklyreport.repository.ValorantMatchRecordRepository;
import com.gamerin.backend.domain.weeklyreport.repository.WeeklyGameReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 발로란트 주간 전적 리포트의 수집·집계·분석·조회. 외부 호출이 길어 트랜잭션으로 묶지 않는다. */
@Service
public class WeeklyReportService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportService.class);
    private static final String GAME = GameType.VALORANT.name();

    private final HenrikValorantClient henrikValorantClient;
    private final WeeklyReportAiClient aiClient;
    private final ValorantMatchRecordRepository matchRecordRepository;
    private final WeeklyGameReportRepository reportRepository;
    private final UserRepository userRepository;
    private final MembershipService membershipService;
    private final Duration userInterval;
    private final Clock clock;

    @Autowired
    public WeeklyReportService(HenrikValorantClient henrikValorantClient, WeeklyReportAiClient aiClient,
            ValorantMatchRecordRepository matchRecordRepository, WeeklyGameReportRepository reportRepository,
            UserRepository userRepository, MembershipService membershipService,
            @Value("${app.weekly-report.user-interval:30s}") Duration userInterval) {
        this(henrikValorantClient, aiClient, matchRecordRepository, reportRepository, userRepository,
                membershipService, userInterval, Clock.systemUTC());
    }

    WeeklyReportService(HenrikValorantClient henrikValorantClient, WeeklyReportAiClient aiClient,
            ValorantMatchRecordRepository matchRecordRepository, WeeklyGameReportRepository reportRepository,
            UserRepository userRepository, MembershipService membershipService, Duration userInterval, Clock clock) {
        this.henrikValorantClient = henrikValorantClient;
        this.aiClient = aiClient;
        this.matchRecordRepository = matchRecordRepository;
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.membershipService = membershipService;
        this.userInterval = userInterval;
        this.clock = clock;
    }

    /** 활성 멤버 본인의 직전 완료 주 리포트. 조회 시점에 생성하지 않는다. */
    public WeeklyGameReportResponse getMyReport(UUID userId) {
        if (!membershipService.isActive(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "멤버십 전용 콘텐츠입니다.");
        }
        WeeklyReportPeriod period = WeeklyReportPeriod.lastCompleted(clock);
        return connectedProfile(userId)
                .flatMap(profile -> reportRepository.findByPuuidAndGameAndWeekStart(
                        profile.getValorantPuuid(), GAME, period.weekStart()))
                .map(report -> toResponse(period, report, false))
                .orElseGet(() -> WeeklyGameReportResponse.empty(period));
    }

    /** 관리자 시연용. Riot ID의 직전 완료 주 리포트를 즉시 다시 만든다. */
    public WeeklyGameReportResponse generateForRiotId(String riotId, ValorantRegion region) {
        String[] parts = ValorantService.parseRiotId(riotId);
        ValorantAccount account = henrikValorantClient.findAccount(parts[0], parts[1]);
        WeeklyReportPeriod period = WeeklyReportPeriod.lastCompleted(clock);
        return toResponse(period, generate(account.puuid(), region, period), true);
    }

    /** 발로란트를 연동한 활성 멤버의 리포트를 만든다. 이미 만든 주는 건너뛴다. */
    public void generateForActiveMembers() {
        WeeklyReportPeriod period = WeeklyReportPeriod.lastCompleted(clock);
        Set<UUID> userIds = membershipService.findActiveUserIds(userRepository.findConnectedValorantUserIds());
        log.info("Weekly report batch started. weekStart={} targets={}", period.weekStart(), userIds.size());
        boolean called = false;
        for (UUID userId : userIds) {
            UserProfile profile = connectedProfile(userId).orElse(null);
            ValorantRegion region = profile == null ? null : parseRegion(profile.getValorantRegion());
            if (region == null) {
                continue;
            }
            String puuid = profile.getValorantPuuid();
            Optional<WeeklyGameReport> existing = reportRepository.findByPuuidAndGameAndWeekStart(
                    puuid, GAME, period.weekStart());
            if (existing.filter(report -> report.getStatus() != WeeklyReportStatus.FAILED).isPresent()) {
                continue;
            }
            if (called && !pause()) {
                return;
            }
            called = true;
            try {
                generate(puuid, region, period);
            } catch (RuntimeException e) {
                // 한 사용자의 실패가 나머지 대상을 막지 않게 한다.
                log.warn("Weekly report generation failed. userId={} error={}", userId, e.getMessage());
                saveFailure(puuid, period);
            }
        }
        log.info("Weekly report batch finished. weekStart={}", period.weekStart());
    }

    WeeklyGameReport generate(String puuid, ValorantRegion region, WeeklyReportPeriod period) {
        List<WeeklyReportCollectionIssue> issues = collect(puuid, region, period);
        WeeklyStats calculated = WeeklyStatsCalculator.calculate(
                matchRecordRepository.findByPuuidAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
                        puuid, period.previousStartInstant(), period.endExclusive()),
                period);
        WeeklyStats stats = new WeeklyStats(calculated.current(), calculated.previous(), calculated.tierChange(), issues);
        if (stats.current() == null) {
            return save(puuid, period, WeeklyReportStatus.NO_DATA, stats, null, null);
        }
        Optional<WeeklyAnalysis> aiAnalysis = aiClient.analyze(stats);
        return save(puuid, period, WeeklyReportStatus.READY, stats,
                aiAnalysis.orElseGet(() -> WeeklyAnalysisFallback.write(stats)),
                aiAnalysis.isPresent() ? aiClient.model() : null);
    }

    // 최신 저장 경기 이후를 수집하고, 최근 이력으로 확인되는 과거 누락도 개별 조회한다.
    private List<WeeklyReportCollectionIssue> collect(String puuid, ValorantRegion region, WeeklyReportPeriod period) {
        Instant since = period.previousStartInstant().toInstant();
        Instant latest = matchRecordRepository.findFirstByPuuidOrderByStartedAtDesc(puuid)
                .map(record -> record.getStartedAt().toInstant())
                .orElse(since);
        List<ValorantMatchDetail> matches = new ArrayList<>(henrikValorantClient.getCompetitiveMatchesSince(
                region, puuid, latest.isAfter(since) ? latest : since));
        List<WeeklyReportCollectionIssue> issues = checkMissingMatches(puuid, region, period, matches);
        if (matches.isEmpty()) {
            return issues;
        }
        Set<String> existing = Set.copyOf(matchRecordRepository.findExistingMatchIds(
                puuid, matches.stream().map(ValorantMatchDetail::matchId).toList()));
        // shortcut: 같은 계정을 동시에 수집하면 유일 키 충돌로 한쪽이 실패한다. 단일 배치 + 관리자 수동 호출 전제다.
        matchRecordRepository.saveAll(matches.stream()
                .filter(match -> !existing.contains(match.matchId()))
                .map(match -> ValorantMatchRecord.of(puuid, match))
                .toList());
        return issues;
    }

    private List<WeeklyReportCollectionIssue> checkMissingMatches(String puuid, ValorantRegion region,
            WeeklyReportPeriod period, List<ValorantMatchDetail> matches) {
        List<WeeklyReportCollectionIssue> issues = new ArrayList<>();
        // 이전 생성에서 못 찾은 경기. 최근 이력 20건 밖으로 밀려도 복구를 확인하기 전에는 진단을 유지한다.
        List<WeeklyReportCollectionIssue> unresolved = reportRepository
                .findByPuuidAndGameAndWeekStart(puuid, GAME, period.weekStart())
                .map(WeeklyGameReport::getStats)
                .map(WeeklyStats::collectionIssues)
                .orElse(List.of()).stream()
                .filter(issue -> issue.matchId() != null)
                .toList();
        List<ValorantMatchReference> history;
        try {
            history = henrikValorantClient.getCompetitiveMatchHistory(region, puuid).stream()
                    .filter(match -> !match.startedAt().isBefore(period.previousStartInstant().toInstant())
                            && match.startedAt().isBefore(period.endExclusive().toInstant()))
                    .toList();
        } catch (ResponseStatusException e) {
            issues.add(new WeeklyReportCollectionIssue(null, null, null, e.getStatusCode().value(),
                    "HenrikDev 랭크 이력을 조회하지 못해 누락 여부를 대조하지 못했습니다. " + e.getReason()));
            issues.addAll(unresolved);
            return issues;
        }
        List<ValorantMatchReference> references = new ArrayList<>(history);
        Set<String> inHistory = history.stream().map(ValorantMatchReference::matchId).collect(Collectors.toSet());
        unresolved.stream()
                .filter(issue -> !inHistory.contains(issue.matchId()))
                .forEach(issue -> references.add(
                        new ValorantMatchReference(issue.matchId(), issue.startedAt(), issue.map())));
        if (references.isEmpty()) {
            return issues;
        }
        Set<String> known = new HashSet<>(matchRecordRepository.findExistingMatchIds(
                puuid, references.stream().map(ValorantMatchReference::matchId).toList()));
        matches.forEach(match -> known.add(match.matchId()));
        for (ValorantMatchReference reference : references) {
            if (known.contains(reference.matchId())) {
                continue;
            }
            try {
                Optional<ValorantMatchDetail> detail = henrikValorantClient.getMatchDetail(region, puuid, reference.matchId());
                if (detail.isPresent()) {
                    matches.add(detail.get());
                    known.add(reference.matchId());
                } else {
                    issues.add(new WeeklyReportCollectionIssue(reference.matchId(), reference.startedAt(), reference.map(),
                            404, "랭크 이력에는 있지만 HenrikDev 경기 상세 API에서 경기 정보를 찾을 수 없습니다."));
                }
            } catch (ResponseStatusException e) {
                issues.add(new WeeklyReportCollectionIssue(reference.matchId(), reference.startedAt(), reference.map(),
                        e.getStatusCode().value(), "HenrikDev 경기 상세 조회에 실패해 추가 확인을 중단했습니다. " + e.getReason()));
                break;
            }
        }
        // 조회가 중단돼 다시 확인하지 못한 이전 누락은 그대로 남긴다.
        Set<String> reported = issues.stream().map(WeeklyReportCollectionIssue::matchId).collect(Collectors.toSet());
        unresolved.stream()
                .filter(issue -> !known.contains(issue.matchId()) && !reported.contains(issue.matchId()))
                .forEach(issues::add);
        return issues;
    }

    // 배치가 외부 API를 기다리는 사이 관리자 생성이 끝낸 리포트를 실패 기록으로 지우지 않는다.
    // shortcut: 재확인과 저장 사이의 짧은 틈은 남는다. 생성 경로가 늘면 조건부 UPDATE로 바꾼다.
    private void saveFailure(String puuid, WeeklyReportPeriod period) {
        boolean completed = reportRepository.findByPuuidAndGameAndWeekStart(puuid, GAME, period.weekStart())
                .filter(report -> report.getStatus() != WeeklyReportStatus.FAILED)
                .isPresent();
        if (!completed) {
            save(puuid, period, WeeklyReportStatus.FAILED, null, null, null);
        }
    }

    private WeeklyGameReport save(String puuid, WeeklyReportPeriod period, WeeklyReportStatus status,
            WeeklyStats stats, WeeklyAnalysis analysis, String aiModel) {
        WeeklyGameReport report = reportRepository.findByPuuidAndGameAndWeekStart(puuid, GAME, period.weekStart())
                .orElseGet(() -> WeeklyGameReport.create(puuid, GAME, period.weekStart()));
        report.update(status, stats, analysis, aiModel);
        return reportRepository.save(report);
    }

    private WeeklyGameReportResponse toResponse(WeeklyReportPeriod period, WeeklyGameReport report, boolean admin) {
        if (report.getStatus() != WeeklyReportStatus.READY) {
            return WeeklyGameReportResponse.empty(period, report.getStats(), admin);
        }
        return WeeklyGameReportResponse.valorant(period, report.getStats(), report.getAnalysis(), admin);
    }

    private Optional<UserProfile> connectedProfile(UUID userId) {
        return userRepository.findWithProfileById(userId)
                .map(User::getProfile)
                .filter(profile -> profile.hasConnectedValorant() && profile.getValorantPuuid() != null);
    }

    private ValorantRegion parseRegion(String value) {
        try {
            return value == null ? null : ValorantRegion.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // HenrikDev 분당 한도를 사용자들이 나눠 쓰도록 간격을 둔다.
    private boolean pause() {
        try {
            Thread.sleep(userInterval.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
