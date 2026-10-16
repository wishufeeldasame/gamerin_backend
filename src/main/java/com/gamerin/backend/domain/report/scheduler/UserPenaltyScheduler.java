package com.gamerin.backend.domain.report.scheduler;

import com.gamerin.backend.domain.report.service.UserPenaltyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정지 만료일시(endAt)가 지난 제재를 주기적으로 자동 해제하고 유저를 복구하는 배치 스케줄러
 */
@Component
public class UserPenaltyScheduler {

    private static final Logger log = LoggerFactory.getLogger(UserPenaltyScheduler.class);

    private final UserPenaltyService userPenaltyService;

    public UserPenaltyScheduler(UserPenaltyService userPenaltyService) {
        this.userPenaltyService = userPenaltyService;
    }

    /**
     * 매 1분마다 만료 시점이 지난 활성 제재 자동 해제
     * (cron: 매 분 0초 실행)
     */
    @Scheduled(cron = "0 * * * * *")
    public void processExpiredPenalties() {
        try {
            int releasedCount = userPenaltyService.releaseExpiredPenalties();
            if (releasedCount > 0) {
                log.info("[UserPenaltyScheduler] 만료된 유저 제재 {}건이 자동 해제(ACTIVE 복구)되었습니다.", releasedCount);
            }
        } catch (Exception e) {
            log.error("[UserPenaltyScheduler] 만료 제재 자동 해제 스케줄러 실행 중 오류 발생", e);
        }
    }
}