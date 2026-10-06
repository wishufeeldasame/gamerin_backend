package com.gamerin.backend.domain.admin.service;

import com.gamerin.backend.domain.admin.entity.AdminAuditLog;
import com.gamerin.backend.domain.admin.repository.AdminAuditLogRepository;
import com.gamerin.backend.domain.admin.dto.response.AdminMentorResponse;
import com.gamerin.backend.domain.admin.dto.response.AdminMentoringProgramResponse;
import com.gamerin.backend.domain.admin.dto.response.AdminMentoringSummaryResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringApplicationResponse;
import com.gamerin.backend.domain.mentoring.entity.*;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.repository.ReportRepository;
import com.gamerin.backend.domain.user.entity.TransactionType;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 어드민 멘토링 운영 관리 (요약 지표, 멘토 심사, 프로그램 관리, 에스크로 강제 개입) 서비스
 */
@Service
@Transactional
public class AdminMentoringService {

        private final MentoringApplicationRepository mentoringApplicationRepository;
        private final MentoringProgramRepository mentoringProgramRepository;
        private final MentorProfileRepository mentorProfileRepository;
        private final ReportRepository reportRepository;
        private final MileageService mileageService;
        private final AdminAuditLogRepository adminAuditLogRepository;
        private final UserRepository userRepository;

        public AdminMentoringService(
                        MentoringApplicationRepository mentoringApplicationRepository,
                        MentoringProgramRepository mentoringProgramRepository,
                        MentorProfileRepository mentorProfileRepository,
                        ReportRepository reportRepository,
                        MileageService mileageService,
                        AdminAuditLogRepository adminAuditLogRepository,
                        UserRepository userRepository) {
                this.mentoringApplicationRepository = mentoringApplicationRepository;
                this.mentoringProgramRepository = mentoringProgramRepository;
                this.mentorProfileRepository = mentorProfileRepository;
                this.reportRepository = reportRepository;
                this.mileageService = mileageService;
                this.adminAuditLogRepository = adminAuditLogRepository;
                this.userRepository = userRepository;
        }

        // ================= [1. 멘토링 운영 요약 지표] =================

        /**
         * 상단 통계 카드 4종 지표 조회
         */
        @Transactional(readOnly = true)
        public AdminMentoringSummaryResponse getMentoringSummary() {
                long pendingMentorCount = mentorProfileRepository.countByStatus(MentorStatus.PENDING_APPROVAL);
                long activeProgramCount = mentoringProgramRepository
                                .countByStatusAndDeletedAtIsNull(ProgramStatus.ACTIVE);

                OffsetDateTime startOfMonth = OffsetDateTime.now()
                                .withDayOfMonth(1)
                                .withHour(0)
                                .withMinute(0)
                                .withSecond(0)
                                .withNano(0);
                long monthlySessionCount = mentoringApplicationRepository.countByCreatedAtAfter(startOfMonth);
                long escrowHeldAmount = mentoringApplicationRepository.sumEscrowHeldMileage();

                return new AdminMentoringSummaryResponse(
                                pendingMentorCount,
                                activeProgramCount,
                                monthlySessionCount,
                                escrowHeldAmount);
        }

        // ================= [2. 멘토 신청 심사 및 관리] =================

        /**
         * 멘토 심사/관리 목록 페이징 조회
         */
        @Transactional(readOnly = true)
        public Page<AdminMentorResponse> getMentors(MentorStatus status, Pageable pageable) {
                Page<MentorProfile> page = (status != null)
                                ? mentorProfileRepository.findByStatus(status, pageable)
                                : mentorProfileRepository.findAll(pageable);
                return page.map(AdminMentorResponse::from);
        }

        /**
         * 멘토 신청 승인
         */
        public AdminMentorResponse approveMentor(UUID adminId, UUID userId) {
            User admin = findAdmin(adminId);
            MentorProfile profile = mentorProfileRepository.findById(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "멘토 프로필 정보를 찾을 수 없습니다."));
    
            profile.setStatus(MentorStatus.ACTIVE);
            MentorProfile saved = mentorProfileRepository.save(profile);
    
            adminAuditLogRepository.save(AdminAuditLog.create(
                    admin,
                    "MENTOR_APPROVE",
                    ReportTargetType.USER,
                    userId,
                    null,
                    String.format("멘토 신청 승인 (@%s, %s)", profile.getUser().getHandle(), profile.getUser().getNickname())
            ));
    
            return AdminMentorResponse.from(saved);
        }

        /**
         * 멘토 신청 반려 또는 비활성화
         */
        public AdminMentorResponse rejectMentor(UUID adminId, UUID userId, String reason) {
            User admin = findAdmin(adminId);
            MentorProfile profile = mentorProfileRepository.findById(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "멘토 프로필 정보를 찾을 수 없습니다."));
    
            profile.setStatus(MentorStatus.INACTIVE);
            MentorProfile saved = mentorProfileRepository.save(profile);
    
            adminAuditLogRepository.save(AdminAuditLog.create(
                    admin,
                    "MENTOR_REJECT",
                    ReportTargetType.USER,
                    userId,
                    null,
                    String.format("멘토 신청 반려/비활성화 (@%s, 사유: %s)", profile.getUser().getHandle(),reason)
            ));
    
            return AdminMentorResponse.from(saved);
        }

        // ================= [3. 멘토링 프로그램 관리] =================

        /**
         * 전체 멘토링 프로그램 목록 조회 (세션 수, 평점, 신고 건수 결합)
         */
        @Transactional(readOnly = true)
        public Page<AdminMentoringProgramResponse> getPrograms(ProgramStatus status, String keyword,
                        Pageable pageable) {
                return mentoringProgramRepository.searchProgramsForAdmin(status, keyword, pageable)
                                .map(program -> {
                                        long sessions = mentoringApplicationRepository
                                                        .countByProgramId(program.getId());
                                        long reports = reportRepository.countByTargetTypeAndTargetId(
                                                        ReportTargetType.MENTORING,
                                                        program.getId());
                                        Double rating = (program.getMentor().getRatingAvg() != null)
                                                        ? program.getMentor().getRatingAvg().doubleValue()
                                                        : null;
                                        return AdminMentoringProgramResponse.of(program, sessions, rating, reports);
                                });
        }

        /**
         * 프로그램 운영 상태 변경 (운영 중 ACTIVE <-> 일시정지 CLOSED)
         */
        public AdminMentoringProgramResponse updateProgramStatus(UUID adminId, UUID programId, ProgramStatus newStatus) {
            User admin = findAdmin(adminId);
            MentoringProgram program = mentoringProgramRepository.findById(programId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "멘토링 프로그램을 찾을 수 없습니다."));
    
            ProgramStatus oldStatus = program.getStatus();
            program.setStatus(newStatus);
            MentoringProgram saved = mentoringProgramRepository.save(program);
    
            adminAuditLogRepository.save(AdminAuditLog.create(
                    admin,
                    "PROGRAM_STATUS_CHANGE",
                    ReportTargetType.MENTORING,
                    programId,
                    null,
                    String.format("프로그램 상태 변경 (%s -> %s, 제목: %s)", oldStatus, newStatus, program.getTitle())
            ));
    
            long sessions = mentoringApplicationRepository.countByProgramId(saved.getId());
            long reports = reportRepository.countByTargetTypeAndTargetId(ReportTargetType.MENTORING, saved.getId());
            Double rating = (saved.getMentor().getRatingAvg() != null)
                    ? saved.getMentor().getRatingAvg().doubleValue()
                    : null;
            return AdminMentoringProgramResponse.of(saved, sessions, rating, reports);
        }

        /**
         * 프로그램 관리자 강제 숨김 (소프트 삭제 및 감사 로그)
         */
        public AdminMentoringProgramResponse hideProgram(UUID adminId, UUID programId, String reason) {
            User admin = findAdmin(adminId);
            MentoringProgram program = mentoringProgramRepository.findById(programId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "멘토링 프로그램을 찾을 수 없습니다."));
    
            if (program.getDeletedAt() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미 숨김 처리된 프로그램입니다.");
            }
    
            program.softDelete();
            MentoringProgram saved = mentoringProgramRepository.save(program);
    
            adminAuditLogRepository.save(AdminAuditLog.create(
                    admin,
                    "PROGRAM_HIDE",
                    ReportTargetType.MENTORING,
                    programId,
                    null,
                    String.format("관리자 강제 숨김 처리 (제목: %s, 사유: %s)", program.getTitle(), reason)
            ));
    
            long sessions = mentoringApplicationRepository.countByProgramId(saved.getId());
            long reports = reportRepository.countByTargetTypeAndTargetId(ReportTargetType.MENTORING, saved.getId());
            Double rating = (saved.getMentor().getRatingAvg() != null)
                    ? saved.getMentor().getRatingAvg().doubleValue()
                    : null;
            return AdminMentoringProgramResponse.of(saved, sessions, rating, reports);
        }

        // ================= [4. 분쟁 에스크로 강제 개입 (기존)] =================

        /**
         * 관리자 강제 환불 (멘티에게 전액 에스크로 마일리지 반환)
         */
        public MentoringApplicationResponse forceRefund(UUID adminId, UUID applicationId, String reason) {
                User admin = findAdmin(adminId);
                MentoringApplication application = findApplication(applicationId);

                if (application.getPaymentStatus() != PaymentStatus.ESCROW_HELD) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "에스크로 보관(ESCROW_HELD) 상태인 멘토링 건만 강제 환불할 수 있습니다. 현재 결제상태: " +
                                                        application.getPaymentStatus());
                }

                application.setStatus(ApplicationStatus.CANCELLED);
                application.setPaymentStatus(PaymentStatus.REFUNDED);

                mileageService.addMileage(
                                application.getMentee(),
                                application.getAppliedMileage(),
                                TransactionType.MENTORING_REFUND,
                                "관리자 강제 개입에 따른 전액 환불 (사유: " + reason + ")",
                                application.getId());

                adminAuditLogRepository.save(AdminAuditLog.create(
                                admin,
                                "FORCE_REFUND",
                                ReportTargetType.MENTORING,
                                applicationId,
                                null,
                                String.format("멘티 환불 금액: %d P, 사유: %s", application.getAppliedMileage(), reason)));

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
                                        "에스크로 보관(ESCROW_HELD) 상태인 멘토링 건만 강제 정산할 수 있습니다. 현재 결제상태: " +
                                                        application.getPaymentStatus());
                }

                application.setStatus(ApplicationStatus.COMPLETED);
                application.setPaymentStatus(PaymentStatus.SETTLED);
                application.setCompletedAt(OffsetDateTime.now());

                MentorProfile mentorProfile = application.getProgram().getMentor();
                mileageService.addMileage(
                                mentorProfile.getUser(),
                                application.getAppliedMileage(),
                                TransactionType.SETTLEMENT,
                                "관리자 강제 개입에 따른 멘토링 정산 (사유: " + reason + ")",
                                application.getId());

                mentorProfile.setMenteeCount(mentorProfile.getMenteeCount() + 1);

                adminAuditLogRepository.save(AdminAuditLog.create(
                                admin,
                                "FORCE_SETTLE",
                                ReportTargetType.MENTORING,
                                applicationId,
                                null,
                                String.format("멘토 정산 지급 금액: %d P, 사유: %s", application.getAppliedMileage(), reason)));

                return MentoringApplicationResponse.from(application);
        }

        private User findAdmin(UUID adminId) {
            return userRepository.findById(adminId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "어드민 계정을 찾을 수 없습니다."));
        }

        private MentoringApplication findApplication(UUID applicationId) {
            return mentoringApplicationRepository.findByIdForUpdate(applicationId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "멘토링 신청 내역을 찾을 수 없습니다."));
        }
}