package com.gamerin.backend.domain.weeklyreport.service;

import com.gamerin.backend.domain.weeklyreport.model.WeekStats;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyAnalysis;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyStats;

/** AI를 쓸 수 없을 때 집계 숫자만으로 만드는 규칙 기반 문장. */
final class WeeklyAnalysisFallback {

    private static final int SMALL_SAMPLE = 5;

    private WeeklyAnalysisFallback() {
    }

    static WeeklyAnalysis write(WeeklyStats stats) {
        WeekStats current = stats.current();
        WeekStats previous = stats.previous();
        return new WeeklyAnalysis(headline(current, previous), summary(current, previous), focus(current));
    }

    private static String headline(WeekStats current, WeekStats previous) {
        if (previous == null) {
            return "경쟁전 %d경기, 승률 %s%%를 기록한 한 주".formatted(current.matches(), number(current.winRate()));
        }
        int diff = Double.compare(current.winRate(), previous.winRate());
        if (diff == 0) {
            return "승률 %s%%를 유지한 한 주".formatted(number(current.winRate()));
        }
        return "승률이 %s%%에서 %s%%로 %s 한 주".formatted(number(previous.winRate()), number(current.winRate()),
                diff > 0 ? "오른" : "내려간");
    }

    private static String summary(WeekStats current, WeekStats previous) {
        StringBuilder text = new StringBuilder("확인된 경쟁전 %d경기에서 승률 %s%%".formatted(
                current.matches(), number(current.winRate())));
        if (current.kd() != null) {
            text.append(", K/D ").append(number(current.kd()));
        }
        if (current.headshotRate() != null) {
            text.append(", HS% ").append(number(current.headshotRate())).append('%');
        }
        text.append("를 기록했습니다.");
        if (current.tierLabel() != null) {
            text.append(" 주말 기준 티어는 ").append(current.tierLabel()).append("입니다.");
        }
        if (previous == null) {
            text.append(" 전주에 확인된 경쟁전 기록이 없어 변화는 비교하지 않았습니다.");
        } else {
            text.append(" 전주에는 %d경기에서 승률 %s%%였습니다.".formatted(
                    previous.matches(), number(previous.winRate())));
        }
        if (current.matches() < SMALL_SAMPLE || (previous != null && previous.matches() < SMALL_SAMPLE)) {
            text.append(" 경기 수가 적어 이 수치만으로 추세를 단정하기는 어렵습니다.");
        }
        return text.toString();
    }

    private static String focus(WeekStats current) {
        if (current.kd() != null && current.kd() < 1.0) {
            return "데스가 킬보다 많았습니다. 첫 교전에서 팀원의 지원을 받을 수 있는 위치인지 확인해 보세요.";
        }
        if (current.headshotRate() != null && current.headshotRate() < 20.0) {
            return "교전에 들어가기 전에 조준선을 머리 높이에 맞춰 두는 습관을 점검해 보세요.";
        }
        return "패배한 경기를 다시 보며 같은 상황에서 반복된 선택이 있는지 확인해 보세요.";
    }

    // 12.0 → "12", 12.5 → "12.5"
    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
