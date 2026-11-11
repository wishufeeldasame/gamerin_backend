package com.gamerin.backend.domain.valorant.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.valorant.model.ValorantMatchDetail;
import com.gamerin.backend.domain.weeklyreport.entity.ValorantMatchRecord;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight.Group;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyReportPeriod;
import com.gamerin.backend.domain.weeklyreport.client.WeeklyReportAiClient;
import com.gamerin.backend.domain.weeklyreport.service.WeeklyInsightCalculator;
import com.gamerin.backend.domain.weeklyreport.service.WeeklyStatsCalculator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 로컬 고정 자료(aiReport, 저장소 밖)가 있을 때만 README 8절 수치와 대조한다. 자료에는 식별정보가 있어 복사하지 않는다.
 * 집계 결과(식별정보 없음)는 build/weekly-insight-fixture.txt 에 남긴다.
 */
class WeeklyInsightFixtureTest {

    // 다른 수집본으로 요청 본문만 만들 때 WEEKLY_FIXTURE·WEEKLY_REQUEST_OUT 환경변수로 바꾼다(README 수치 대조는 생략).
    private static final boolean CUSTOM = System.getenv("WEEKLY_FIXTURE") != null;
    private static final Path FIXTURE = CUSTOM ? Path.of(System.getenv("WEEKLY_FIXTURE"))
            : Path.of("..", "aiReport", "data", "20261110-215300-086631", "matches_report_weeks.json");

    private static final Path REQUEST_OUT = System.getenv("WEEKLY_REQUEST_OUT") != null
            ? Path.of(System.getenv("WEEKLY_REQUEST_OUT")) : Path.of("..", "aiReport", "out", "ai_request.json");

    @Test
    void matchesReadmeNumbers() throws Exception {
        Assumptions.assumeTrue(Files.exists(FIXTURE), "고정 자료 없음");
        JsonNode data = new ObjectMapper().readTree(FIXTURE.toFile()).path("data");

        // 모든 경기에 공통인 플레이어가 대상이다.
        Set<String> common = null;
        for (JsonNode match : data) {
            Set<String> ids = new HashSet<>();
            match.path("players").forEach(p -> ids.add(p.path("puuid").asText()));
            if (common == null) {
                common = ids;
            } else {
                common.retainAll(ids);
            }
        }
        assertThat(common).hasSize(1);
        String puuid = common.iterator().next();

        HenrikValorantClient client = new HenrikValorantClient("", "", Duration.ofSeconds(1), Duration.ofSeconds(1));
        List<ValorantMatchRecord> records = new ArrayList<>();
        for (JsonNode match : data) {
            Instant startedAt = Instant.parse(match.path("metadata").path("started_at").asText());
            ValorantMatchDetail detail = client.toMatchDetail(match, puuid, startedAt);
            assertThat(detail).isNotNull();
            assertThat(detail.insight()).isNotNull();
            records.add(ValorantMatchRecord.of(puuid, detail));
        }

        WeeklyInsight insight = WeeklyInsightCalculator.calculate(records,
                new WeeklyReportPeriod(LocalDate.of(2026, 11, 2)));
        Files.writeString(Path.of("build", "weekly-insight-fixture.txt"), insight.toString().replace("), ", "),\n"));

        // 4단계 실호출용: 서비스와 같은 system 프롬프트·user 입력으로 만든 요청 본문(model 제외). 저장소 밖에 쓴다.
        WeeklyReportPeriod period = new WeeklyReportPeriod(LocalDate.of(2026, 11, 2));
        ObjectMapper mapper = new ObjectMapper();
        String body = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(WeeklyReportAiClient.requestBody(
                WeeklyStatsCalculator.calculate(records, period), insight, mapper));
        Files.createDirectories(REQUEST_OUT.getParent());
        Files.writeString(REQUEST_OUT, body);
        assertThat(body).doesNotContain(puuid, data.get(0).path("metadata").path("match_id").asText());

        if (CUSTOM) {
            return;
        }
        WeeklyInsight.Stats current = insight.current().overall();
        assertThat(current.matches()).isEqualTo(9);
        assertThat(current.insightMatches()).isEqualTo(9);
        assertThat(current.acs()).isEqualTo(237.6);
        assertThat(current.evidence().adr()).isEqualTo(154.2);
        assertThat(current.evidence().firstKills()).isEqualTo(22);
        assertThat(current.evidence().firstDeaths()).isEqualTo(18);

        WeeklyInsight.Stats previous = insight.previous().overall();
        assertThat(previous.matches()).isEqualTo(14);
        assertThat(previous.acs()).isEqualTo(260.4);
        assertThat(previous.evidence().adr()).isEqualTo(173.5);
        assertThat(previous.evidence().firstKills()).isEqualTo(45);
        assertThat(previous.evidence().firstDeaths()).isEqualTo(47);

        Group jett = insight.current().byAgent().stream().filter(g -> g.label().equals("Jett")).findFirst().orElseThrow();
        assertThat(jett.stats().evidence().firstKills()).isEqualTo(18);
        assertThat(jett.stats().evidence().firstDeaths()).isEqualTo(13);
    }
}
