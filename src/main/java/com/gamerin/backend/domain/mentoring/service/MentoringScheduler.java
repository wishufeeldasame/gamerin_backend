package com.gamerin.backend.domain.mentoring.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MentoringScheduler {
    
    private final MentoringService mentoringService;

    public MentoringScheduler(MentoringService mentoringService) {
        this.mentoringService = mentoringService;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void autoSettlementJob() {
        System.out.println("자동 정산 스케줄러 시작...");
        mentoringService.processAutoSettlement();
        System.out.println("자동 정산 스케줄러 종료.");
    }
}
