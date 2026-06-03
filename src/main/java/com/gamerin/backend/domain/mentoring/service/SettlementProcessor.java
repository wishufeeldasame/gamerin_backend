package com.gamerin.backend.domain.mentoring.service;

import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.PaymentStatus;
import com.gamerin.backend.domain.user.entity.TransactionType;
import com.gamerin.backend.domain.user.service.MileageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Component
public class SettlementProcessor {

    private static final Logger log =
LoggerFactory.getLogger(SettlementProcessor.class);

    private final MileageService mileageService;

    // Lombok의 @RequiredArgsConstructor 대신 직접 생성자 작성
    public SettlementProcessor(MileageService mileageService) {
        this.mileageService = mileageService;
    }

    /**
     * 개별 신청 건에 대해 독립적인 트랜잭션으로 정산을 수행합니다.
     * REQUIRES_NEW를 사용하여 이전 트랜잭션과 상관없이 개별적으로
커밋/롤백됩니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleSettlement(MentoringApplication application) {
        // 1. 상태 변경
        application.setStatus(ApplicationStatus.COMPLETED);
        application.setPaymentStatus(PaymentStatus.SETTLED);
        application.setCompletedAt(OffsetDateTime.now());

        // 2. 멘토 마일리지 정산
        MentorProfile mentorProfile = application.getProgram().getMentor();
        mileageService.addMileage(
            mentorProfile.getUser(),
            application.getAppliedMileage(),
            TransactionType.SETTLEMENT,
            "멘토링 7일 경과 자동 정산",
            application.getId()
        );

        // 3. 멘토 통계 업데이트
        mentorProfile.setMenteeCount(mentorProfile.getMenteeCount() + 1);
        
        log.info("자동 정산 성공 - ID: {}", application.getId());
    }
}