package com.gamerin.backend.domain.mentoring.service;

import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.PaymentStatus;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.notification.service.NotificationCommandService;
import com.gamerin.backend.domain.report.entity.ReportStatus;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.repository.ReportRepository;
import com.gamerin.backend.domain.user.entity.TransactionType;
import com.gamerin.backend.domain.user.service.MileageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class SettlementProcessor {

        private static final Logger log = LoggerFactory.getLogger(SettlementProcessor.class);

        // 자동 정산을 보류해야 하는 미결 신고 상태 목록 (접수됨, 검토 중)
        private static final List<ReportStatus> UNRESOLVED_REPORT_STATUSES = List.of(
                        ReportStatus.RECEIVED,
                        ReportStatus.IN_REVIEW);

        private final MileageService mileageService;
        private final MentoringApplicationRepository mentoringApplicationRepository;
        private final MentorProfileRepository mentorProfileRepository;
        private final NotificationCommandService notificationCommandService;
        private final ReportRepository reportRepository;

        // Lombok의 @RequiredArgsConstructor 대신 직접 생성자 작성
        public SettlementProcessor(
                        MileageService mileageService,
                        MentoringApplicationRepository mentoringApplicationRepository,
                        MentorProfileRepository mentorProfileRepository,
                        NotificationCommandService notificationCommandService,
                        ReportRepository reportRepository) {
                this.mileageService = mileageService;
                this.mentoringApplicationRepository = mentoringApplicationRepository;
                this.mentorProfileRepository = mentorProfileRepository;
                this.notificationCommandService = notificationCommandService;
                this.reportRepository = reportRepository;
        }

        /**
         * 개별 신청 건에 대해 독립적인 트랜잭션으로 정산을 수행합니다.
         * REQUIRES_NEW를 사용하여 이전 트랜잭션과 상관없이 개별적으로 커밋/롤백됩니다.
         */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void processSingleSettlement(UUID applicationId, OffsetDateTime threshold) {
                MentoringApplication application = mentoringApplicationRepository.findByIdForUpdate(applicationId)
                                .orElse(null);
                if (application == null
                                || application.getStatus() != ApplicationStatus.FINISHED
                                || !application.getUpdatedAt().isBefore(threshold)) {
                        return;
                }

                // [추가] 해당 신청 건에 대해 처리되지 않은 신고(이의제기)가 존재하면 자동 정산을 보류한다.
                boolean hasUnresolvedReport = reportRepository.existsByTargetTypeAndTargetIdAndStatusIn(
                                ReportTargetType.MENTORING,
                                application.getId(),
                                UNRESOLVED_REPORT_STATUSES);
                if (hasUnresolvedReport) {
                        log.info("미결 신고(이의제기)가 존재하여 자동 정산을 보류합니다 - 신청 ID: {}", application.getId());
                        return;
                }

                // 1. 상태 변경
                application.setStatus(ApplicationStatus.COMPLETED);
                application.setPaymentStatus(PaymentStatus.SETTLED);
                application.setCompletedAt(OffsetDateTime.now());

                // 2. 멘토 프로필 비관적 락 조회
                MentorProfile mentorProfile = mentorProfileRepository.findByIdForUpdate(
                                application.getProgram().getMentor().getId())
                                .orElseThrow(() -> new IllegalStateException("Mentor profile not found."));

                // [KP-C1 #90 F3]: 공통 계산기로 수수료 계산, fee_amount 저장 및 멘토 지급액 산출
                MentoringSettlementCalculator.SettlementResult settlement = MentoringSettlementCalculator
                                .applySettlement(application);

                // 3. 멘토 마일리지 정산 (수수료 공제 후 실 수령액 지급)
                mileageService.addMileage(
                                mentorProfile.getUser(),
                                settlement.payoutAmount(),
                                TransactionType.SETTLEMENT,
                                "멘토링 7일 경과 자동 정산",
                                application.getId());

                // 4. 멘토 통계 업데이트
                mentorProfileRepository.incrementMenteeCount(mentorProfile.getUserId());

                notificationCommandService.createMentoringCompleted(application, null, mentorProfile.getUser());
                notificationCommandService.createMentoringCompleted(application, null, application.getMentee());

                log.info("자동 정산 성공 - ID: {}, 지급액: {}, 수수료: {}",
                                application.getId(), settlement.payoutAmount(), settlement.feeAmount());
        }
}