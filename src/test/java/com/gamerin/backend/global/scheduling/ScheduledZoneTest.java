package com.gamerin.backend.global.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.gamerin.backend.domain.mentoring.service.MentoringScheduler;
import com.gamerin.backend.domain.report.scheduler.UserPenaltyScheduler;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

class ScheduledZoneTest {

    private static Scheduled scheduled(Class<?> type, String method) throws Exception {
        Method m = type.getMethod(method);
        return m.getAnnotation(Scheduled.class);
    }

    @Test
    @DisplayName("자동 정산 cron은 KST 기준 03시에 실행된다")
    void autoSettlementRunsAt3amKst() throws Exception {
        Scheduled s = scheduled(MentoringScheduler.class, "autoSettlementJob");
        assertThat(s.zone()).isEqualTo("Asia/Seoul");

        ZoneId zone = ZoneId.of(s.zone());
        ZonedDateTime next = CronExpression.parse(s.cron())
                .next(ZonedDateTime.of(2026, 11, 12, 0, 0, 0, 0, zone));
        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 11, 12, 3, 0, 0, 0, zone));
        // UTC 컨테이너 기준으로는 전날 18:00Z 이다.
        assertThat(next.withZoneSameInstant(ZoneId.of("UTC")).getHour()).isEqualTo(18);
    }

    @Test
    @DisplayName("제재 만료 스케줄러도 KST zone을 명시한다")
    void penaltySchedulerHasZone() throws Exception {
        assertThat(scheduled(UserPenaltyScheduler.class, "processExpiredPenalties").zone())
                .isEqualTo("Asia/Seoul");
    }
}
