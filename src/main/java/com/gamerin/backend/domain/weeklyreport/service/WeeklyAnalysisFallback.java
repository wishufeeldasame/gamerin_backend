package com.gamerin.backend.domain.weeklyreport.service;

import java.util.Comparator;

import com.gamerin.backend.domain.weeklyreport.model.WeeklyAnalysis;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight.Evidence;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight.Group;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight.Stats;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyStats;

/**
 * AI를 쓸 수 없을 때 쓰는 규칙 기반 문장. 화면 수치는 다시 나열하지 않고,
 * 상세 근거(첫 교전·맵 × 요원)로 관찰 1~2개와 복기 행동 하나만 쓴다. 근거가 없으면 판단을 미룬다.
 */
final class WeeklyAnalysisFallback {

    private static final int SMALL_SAMPLE = 2;

    private WeeklyAnalysisFallback() {
    }

    static WeeklyAnalysis write(WeeklyStats stats, WeeklyInsight insight) {
        Stats overall = insight == null || insight.current() == null ? null : insight.current().overall();
        if (overall == null || overall.evidence() == null) {
            return new WeeklyAnalysis("상세 근거가 부족해 해석을 미룬 한 주",
                    "이번 주 경쟁전 %d경기는 경기 상세 근거를 확인하지 못해, 첫 교전이나 맵·요원별 흐름을 해석하지 않았습니다. 상세 근거가 쌓이면 다음 리포트에서 함께 살펴보겠습니다."
                            .formatted(stats.current().matches()),
                    "이번 주 %s 하나를 골라 첫 교전이 일어난 장면을 다시 확인해 보세요."
                            .formatted(stats.current().wins() < stats.current().matches() ? "패배한 경기" : "경기"));
        }
        Evidence current = overall.evidence();
        Stats previousOverall = insight.previous() == null ? null : insight.previous().overall();
        Evidence previous = previousOverall == null ? null : previousOverall.evidence();

        StringBuilder summary = new StringBuilder();
        if (overall.insightMatches() < overall.matches()) {
            summary.append("상세 근거는 %d경기 중 %d경기에서만 확인되어 그 범위 안에서 살펴봤습니다. "
                    .formatted(overall.matches(), overall.insightMatches()));
        }
        Double rate = firstKillRate(current);
        // 한쪽이라도 상세 근거가 일부 경기뿐이면 그 비율을 주 전체 변화로 비교하지 않는다.
        boolean comparable = previous != null && overall.insightMatches() == overall.matches()
                && previousOverall.insightMatches() == previousOverall.matches();
        Double previousRate = comparable ? firstKillRate(previous) : null;
        String headline = "첫 교전과 주요 조합을 함께 돌아볼 한 주";
        if (rate != null && previousRate != null && !rate.equals(previousRate)) {
            boolean up = rate > previousRate;
            headline = up ? "첫 교전에서 먼저 처치한 비율이 오른 한 주" : "첫 교전에서 먼저 처치한 비율이 내려간 한 주";
            summary.append("첫 교전에서 먼저 처치한 비율은 전주 %s%%에서 이번 주 %s%%로 %s습니다. "
                    .formatted(number(previousRate), number(rate), up ? "올랐" : "내려갔"));
        } else if (rate != null) {
            summary.append("첫 교전 %d번 중 먼저 처치한 경우는 %d번이었습니다. "
                    .formatted(current.firstKills() + current.firstDeaths(), current.firstKills()));
        }

        Group top = insight.current().byMapAgent().stream()
                .max(Comparator.comparingInt((Group g) -> g.stats().matches()))
                .orElse(null);
        String combo = top == null ? null : comboSentence(top, overall);
        if (combo != null) {
            summary.append(combo);
        }
        summary.append("이 수치만으로 원인을 단정하기는 어려우므로 경기 장면과 함께 확인하는 것이 좋겠습니다.");

        String focus = top == null
                ? "이번 주 경기에서 첫 데스가 나온 라운드를 골라, 첫 교전에서 먼저 처치한 라운드와 이후 선택을 비교해 보세요."
                : "%s 경기에서 첫 데스가 나온 라운드를 골라, 첫 교전에서 먼저 처치한 라운드와 이후 선택을 비교해 보세요."
                        .formatted(top.label());
        return new WeeklyAnalysis(headline, summary.toString().strip(), focus);
    }

    private static String comboSentence(Group top, Stats overall) {
        Stats stats = top.stats();
        Double adr = stats.evidence() == null ? null : stats.evidence().adr();
        Double overallAdr = overall.evidence().adr();
        if (stats.acs() == null || overall.acs() == null || adr == null || overallAdr == null) {
            return null;
        }
        String text = "경기 수가 가장 많은 %s 조합(%d경기)은 ACS %s, ADR %s로 주 전체(ACS %s, ADR %s)와 비교됩니다. "
                .formatted(top.label(), stats.matches(), number(stats.acs()), number(adr),
                        number(overall.acs()), number(overallAdr));
        if (stats.matches() <= SMALL_SAMPLE) {
            text += "다만 %d경기뿐이라 반복되는 경향인지는 더 지켜봐야 합니다. ".formatted(stats.matches());
        }
        return text;
    }

    // 첫 교전 중 먼저 처치한 비율(%, 소수 1자리). 첫 교전이 없으면 null.
    private static Double firstKillRate(Evidence evidence) {
        int total = evidence.firstKills() + evidence.firstDeaths();
        return total == 0 ? null : Math.round(evidence.firstKills() * 1000.0 / total) / 10.0;
    }

    // 12.0 → "12", 12.5 → "12.5"
    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
