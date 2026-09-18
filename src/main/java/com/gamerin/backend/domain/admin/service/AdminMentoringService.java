package com.gamerin.backend.domain.admin.service;

import com.gamerin.backend.domain.admin.entity.AdminAuditLog;
import com.gamerin.backend.domain.admin.repository.AdminAuditLogRepository;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringApplicationResponse;
import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.PaymentStatus;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.user.entity.TransactionType;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 분쟁 멘토링 건 관리자 에스크로 강제 개입(멘티 환불, 멘토 정산) 집행 및 감사 로그 기록 서비스
 */
@Service
@Transactional
public class AdminMentoringService {

    private final MentoringApplicationRepository mentoringApplicationRepository;
    private final MileageService mileageService;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final UserRepository userRepository;

    public AdminMentoringService(
            MentoringApplicationRepository mentoringApplicationRepository,
            MileageService mileageService,
            AdminAuditLogRepository adminAuditLogRepository,
            UserRepository userRepository
    ) {
        this.mentoringApplicationRepository = mentoringApplicationRepository;
        this.mileageService = mileageService;
        this.adminAuditLogRepository = adminAuditLogRepository;
        this.userRepository = userRepository;
    }

    /**
     * 관리자 강제 환불 (멘티에게 전액 에스크로 마일리지 반환)
     */
    public MentoringApplicationResponse forceRefund(UUID adminId, UUID applicationId, String reason) {
        User admin = findAdmin(adminId);
        MentoringApplication application = findApplication(applicationId);

        // 이미 환불/정산 완료된 건인지 검증
        if (application.getPaymentStatus() != PaymentStatus.ESCROW_HELD) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "에스크로 보관(ESCROW_HELD) 상태인 멘토링 건만 강제 환불할 수 있습니다. 현재 결제상태: " + application.getPaymentStatus());
        }

        // 1. 상태를 취소 및 환불 완료로 변경
        application.setStatus(ApplicationStatus.CANCELLED);
        application.setPaymentStatus(PaymentStatus.REFUNDED);

        // 2. 멘티에게 마일리지 반환 및 트랜잭션 적재
        mileageService.addMileage(
                application.getMentee(),
                application.getAppliedMileage(),
                TransactionType.MENTORING_REFUND,
                "관리자 강제 개입에 따른 전액 환불 (사유: " + reason + ")",
                application.getId()
        );

        // 3. 어드민 감사 로그 적재
        adminAuditLogRepository.save(AdminAuditLog.create(
                admin,
                "FORCE_REFUND",
                ReportTargetType.MENTORING,
                applicationId,
                null,
                String.format("멘티 환불 금액: %d P, 사유: %s", application.getAppliedMileage(), reason)
        ));

        return MentoringApplicationResponse.from(application);
    }

    /**
     * 관리자 강제 정산 (멘토에게 에스크로 마일리지 강제 지급)
     */
    public MentoringApplicationResponse forceSettle(UUID adminId, UUID applicationId, String reason) {
        User admin = findAdmin(adminId);
        MentoringApplication application = findApplication(applicationId);

        if (application.getPaymentStatus() != PaymentStatus.ESCROW_HELD) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "에스크로 보관(ESCROW_HELD) 상태인 멘토링 건만 강제 정산할 수 있습니다. 현재 결제상태: " + application.getPaymentStatus());
        }

        // 1. 상태를 완료 및 정산 완료로 변경
        application.setStatus(ApplicationStatus.COMPLETED);
        application.setPaymentStatus(PaymentStatus.SETTLED);
        application.setCompletedAt(OffsetDateTime.now());

        // 2. 멘토에게 마일리지 정산 지급
        MentorProfile mentorProfile = application.getProgram().getMentor();
        mileageService.addMileage(
                mentorProfile.getUser(),
                application.getAppliedMileage(),
                TransactionType.SETTLEMENT,
                "관리자 강제 개입에 따른 멘토링 정산 (사유: " + reason + ")",
                application.getId()
        );

        // 3. 멘토 누적 멘티 수 증가
        mentorProfile.setMenteeCount(mentorProfile.getMenteeCount() + 1);

        // 4. 어드민 감사 로그 적재
        adminAuditLogRepository.save(AdminAuditLog.create(
                admin,
                "FORCE_SETTLE",
                ReportTargetType.MENTORING,
                applicationId,
                null,
                String.format("멘토 정산 지급 금액: %d P, 사유: %s", application.getAppliedMileage(), reason)
        ));

        return MentoringApplicationResponse.from(application);
    }

    private User findAdmin(UUID adminId) {
        return userRepository.findById(adminId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "어드민 계정을 찾을 수 없습니다."));
    }

    private MentoringApplication findApplication(UUID applicationId) {
        return mentoringApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "멘토링 신청 내역을 찾을 수 없습니다."));
    }
}