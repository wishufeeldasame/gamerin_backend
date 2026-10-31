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
import com.gamerin.backend.domain.mentoring.service.MentoringSettlementCalculator;
import com.gamerin.backend.domain.notification.service.NotificationCommandService;
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
import java.util.List;
import java.util.Map;
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
        private final NotificationCommandService notificationCommandService;

        public AdminMentoringService(
                        MentoringApplicationRepository mentoringApplicationRepository,
                        MentoringProgramRepository mentoringProgramRepository,
                        MentorProfileRepository mentorProfileRepository,
                        ReportRepository reportRepository,
                        MileageService mileageService,
                        AdminAuditLogRepository adminAuditLogRepository,
                        UserRepository userRepository,
                        NotificationCommandService notificationCommandService) {
                this.mentoringApplicationRepository = mentoringApplicationRepository;
                this.mentoringProgramRepository = mentoringProgramRepository;
                this.mentorProfileRepository = mentorProfileRepository;
                this.reportRepository = reportRepository;
                this.mileageService = mileageService;
                this.adminAuditLogRepository = adminAuditLogRepository;
                this.userRepository = userRepository;
                this.notificationCommandService = notificationCommandService;
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
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "멘토 프로필 정보를 찾을 수 없습니다."));

                profile.setStatus(MentorStatus.ACTIVE);
                MentorProfile saved = mentorProfileRepository.save(profile);

                adminAuditLogRepository.save(AdminAuditLog.create(
                                admin,
                                "MENTOR_APPROVE",
                                ReportTargetType.USER,
                                userId,
                                null,
                                String.format("멘토 신청 승인 (@%s, %s)", profile.getUser().getHandle(),
                                                profile.getUser().getNickname())));

                return AdminMentorResponse.from(saved);
        }

        /**
         * 멘토 신청 반려 또는 비활성화
         */
        public AdminMentorResponse rejectMentor(UUID adminId, UUID userId, String reason) {
                User admin = findAdmin(adminId);
                MentorProfile profile = mentorProfileRepository.findById(userId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "멘토 프로필 정보를 찾을 수 없습니다."));

                profile.setStatus(MentorStatus.INACTIVE);
                MentorProfile saved = mentorProfileRepository.save(profile);

                adminAuditLogRepository.save(AdminAuditLog.create(
                                admin,
                                "MENTOR_REJECT",
                                ReportTargetType.USER,
                                userId,
                                null,
                                String.format("멘토 신청 반려/비활성화 (@%s, 사유: %s)", profile.getUser().getHandle(), reason)));

                return AdminMentorResponse.from(saved);
        }

        // ================= [3. 멘토링 프로그램 관리] =================

        /**
         * 전체 멘토링 프로그램 목록 조회 (세션 수, 평점, 신고 건수 결합)
         */
        @Transactional(readOnly = true)
        public Page<AdminMentoringProgramResponse> getPrograms(ProgramStatus status, String keyword,
                        Pageable pageable) {
                Page<MentoringProgram> page = mentoringProgramRepository.searchProgramsForAdmin(status, keyword,
                                pageable);

                // 페이지에 있는 프로그램 ID 목록 추출
                List<UUID> programIds = page.getContent().stream()
                                .map(MentoringProgram::getId)
                                .toList();

                // 신청 건수 일괄 조회 (쿼리 1번)
                Map<UUID, Long> sessionMap = new java.util.HashMap<>();
                for (Object[] row : mentoringApplicationRepository.countByProgramIdIn(programIds)) {
                        sessionMap.put((UUID) row[0], (Long) row[1]);
                }

                // 신고 건수 일괄 조회 (신청 건 기준 신고 + 프로그램 직접 신고 합산)
                Map<UUID, Long> reportMap = countReportsByProgramIds(programIds);

                return page.map(program -> {
                        long sessions = sessionMap.getOrDefault(program.getId(), 0L);
                        long reports = reportMap.getOrDefault(program.getId(), 0L);
                        Double rating = (program.getMentor().getRatingAvg() != null)
                                        ? program.getMentor().getRatingAvg().doubleValue()
                                        : null;
                        return AdminMentoringProgramResponse.of(program, sessions, rating, reports);
                });
        }

        /**
         * 프로그램 운영 상태 변경 (운영 중 ACTIVE <-> 일시정지 CLOSED)
         * - 관리자가 CLOSED 처리 시 adminSuspendedAt을 기록하여 멘토가 임의로 다시 열지 못하도록 방지
         * - 관리자가 ACTIVE 처리 시 adminSuspendedAt을 초기화하여 정지 해제
         */
        public AdminMentoringProgramResponse updateProgramStatus(UUID adminId, UUID programId,
                        ProgramStatus newStatus) {
                User admin = findAdmin(adminId);
                MentoringProgram program = mentoringProgramRepository.findById(programId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "멘토링 프로그램을 찾을 수 없습니다."));

                ProgramStatus oldStatus = program.getStatus();
                program.setStatus(newStatus);

                // 관리자 정지 시각 설정 및 해제
                if (newStatus == ProgramStatus.CLOSED) {
                        program.setAdminSuspendedAt(OffsetDateTime.now());
                } else if (newStatus == ProgramStatus.ACTIVE) {
                        program.setAdminSuspendedAt(null);
                }

                MentoringProgram saved = mentoringProgramRepository.save(program);

                adminAuditLogRepository.save(AdminAuditLog.create(
                                admin,
                                "PROGRAM_STATUS_CHANGE",
                                ReportTargetType.MENTORING,
                                programId,
                                null,
                                String.format("프로그램 상태 변경 (%s -> %s, 제목: %s)", oldStatus, newStatus,
                                                program.getTitle())));

                long sessions = mentoringApplicationRepository.countByProgramId(saved.getId());
                long reports = countProgramReports(saved.getId());
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
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "멘토링 프로그램을 찾을 수 없습니다."));

                if (program.getDeletedAt() != null) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미 숨김 처리된 프로그램입니다.");
                }

                // 진행 중인 에스크로 건이 있어도 어드민 숨김은 허용한다 (악성 프로그램 즉시 차단).
                // 숨김은 신규 노출/신청만 막고 기존 신청 건의 완료·취소·환불·정산 흐름은 신청 건 기준으로 계속 동작한다.
                // 후속 관리가 필요하므로 진행 중 에스크로 건 수를 감사 로그에 남긴다.
                long heldEscrowCount = mentoringApplicationRepository.countByProgramIdAndPaymentStatus(
                                programId, PaymentStatus.ESCROW_HELD);
                String escrowNote = heldEscrowCount > 0 ? " [진행 중 에스크로 " + heldEscrowCount + "건]" : "";
                program.softDelete();
                MentoringProgram saved = mentoringProgramRepository.save(program);

                adminAuditLogRepository.save(AdminAuditLog.create(
                                admin,
                                "PROGRAM_HIDE",
                                ReportTargetType.MENTORING,
                                programId,
                                null,
                                String.format("관리자 강제 숨김 처리 (제목: %s, 사유: %s)", program.getTitle(), reason) + escrowNote));

                long sessions = mentoringApplicationRepository.countByProgramId(saved.getId());
                long reports = countProgramReports(saved.getId());
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

                notificationCommandService.createMentoringRefunded(application, null, application.getMentee());
                if (application.getProgram() != null
                                && application.getProgram().getMentor() != null
                                && application.getProgram().getMentor().getUser() != null) {
                        notificationCommandService.createMentoringRefunded(
                                        application, null, application.getProgram().getMentor().getUser());
                }

                return MentoringApplicationResponse.from(application);
        }

        /**
         * 관리자 강제 정산 (멘토에게 에스크로 마일리지 강제 지급)
         */
        public MentoringApplicationResponse forceSettle(UUID adminId, UUID applicationId, String reason) {
                User admin = findAdmin(adminId);
                MentoringApplication application = findApplication(applicationId);

                if (application.getPaymentStatus() != PaymentStatus.ESCROW_HELD) {
                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "에스크로 보관(ESCROW_HELD) 상태인 멘토링 건만 강제 정산할 수 있습니다. 현재 결제상태: "
                                                        + application.getPaymentStatus());
                }

                application.setStatus(ApplicationStatus.COMPLETED);
                application.setPaymentStatus(PaymentStatus.SETTLED);
                application.setCompletedAt(OffsetDateTime.now());

                // [Bug #83 해결]: mentorProfile을 findByIdForUpdate로 비관적 락을 획득하여 조회 (동시 정산 시
                // menteeCount 유실 방지)
                MentorProfile mentorProfile = mentorProfileRepository
                                .findByIdForUpdate(application.getProgram().getMentor().getId())
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "멘토 프로필을 찾을 수 없습니다."));

                // [KP-C1 #90 F3]: 공통 계산기로 수수료 계산, fee_amount 저장 및 멘토 지급액 산출
                MentoringSettlementCalculator.SettlementResult settlement = MentoringSettlementCalculator
                                .applySettlement(application);

                mileageService.addMileage(
                                mentorProfile.getUser(),
                                settlement.payoutAmount(),
                                TransactionType.SETTLEMENT,
                                "관리자 강제 개입에 따른 멘토링 정산 (사유: " + reason + ")",
                                application.getId());

                mentorProfileRepository.incrementMenteeCount(mentorProfile.getUserId());

                adminAuditLogRepository.save(AdminAuditLog.create(
                                admin,
                                "FORCE_SETTLE",
                                ReportTargetType.MENTORING,
                                applicationId,
                                null,
                                String.format("멘토 정산 지급 금액: %d P, 수수료: %d P, 사유: %s",
                                                settlement.payoutAmount(), settlement.feeAmount(), reason)));

                notificationCommandService.createMentoringCompleted(application, null, mentorProfile.getUser());
                notificationCommandService.createMentoringCompleted(application, null, application.getMentee());

                return MentoringApplicationResponse.from(application);
        }

        private long countProgramReports(UUID programId) {
                return countReportsByProgramIds(List.of(programId)).getOrDefault(programId, 0L);
        }

        /**
         * 프로그램별 신고 수 집계의 유일한 진입점.
         * 프로그램에 대한 신고는 두 경로로 접수되므로 항상 합산한다.
         * (1) 해당 프로그램의 신청 건에 대한 신고 (targetId = 신청 건 ID)
         * (2) 프로그램 자체에 대한 직접 신고 (targetId = 프로그램 ID)
         * 신고 수가 필요한 모든 곳은 이 메서드만 사용해야 한다 (한쪽을 빠뜨리면 화면마다 숫자가 달라진다).
         */
        private Map<UUID, Long> countReportsByProgramIds(List<UUID> programIds) {
                Map<UUID, Long> reportMap = new java.util.HashMap<>();
                if (programIds.isEmpty()) {
                        return reportMap;
                }
                for (Object[] row : reportRepository.countApplicationReportsByProgramIdIn(programIds)) {
                        reportMap.merge((UUID) row[0], (Long) row[1], Long::sum);
                }
                for (Object[] row : reportRepository.countProgramDirectReportsByProgramIdIn(programIds)) {
                        reportMap.merge((UUID) row[0], (Long) row[1], Long::sum);
                }
                return reportMap;
        }
        private User findAdmin(UUID adminId) {
                return userRepository.findById(adminId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "어드민 계정을 찾을 수 없습니다."));
        }

        private MentoringApplication findApplication(UUID applicationId) {
                return mentoringApplicationRepository.findByIdForUpdate(applicationId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "멘토링 신청 내역을 찾을 수 없습니다."));
        }
}