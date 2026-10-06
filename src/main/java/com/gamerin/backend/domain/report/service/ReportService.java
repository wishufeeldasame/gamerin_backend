// 신고/어드민 시스템 통합 서비스 (유저 신고 접수 + 어드민 관리 및 숨김 콘텐츠 실시간 연동 복구 + 원클릭 통합 판정)
package com.gamerin.backend.domain.report.service;

import com.gamerin.backend.domain.admin.entity.AdminAuditLog;
import com.gamerin.backend.domain.admin.repository.AdminAuditLogRepository;
import com.gamerin.backend.domain.admin.repository.SystemConfigRepository;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.message.entity.DirectMessage;
import com.gamerin.backend.domain.message.repository.DirectMessageRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.report.dto.request.AdminReportResolutionRequest;
import com.gamerin.backend.domain.report.dto.request.ReportCreateRequest;
import com.gamerin.backend.domain.report.dto.request.ReportSearchCondition;
import com.gamerin.backend.domain.report.dto.request.ReportStatusUpdateRequest;
import com.gamerin.backend.domain.report.dto.request.UserPenaltyCreateRequest;
import com.gamerin.backend.domain.report.dto.response.AdminReportDetailResponse;
import com.gamerin.backend.domain.report.dto.response.HiddenContentResponse;
import com.gamerin.backend.domain.report.dto.response.ReportReasonResponse;
import com.gamerin.backend.domain.report.dto.response.ReportResponse;
import com.gamerin.backend.domain.report.dto.response.UserPenaltyResponse;
import com.gamerin.backend.domain.report.entity.Report;
import com.gamerin.backend.domain.report.entity.ReportCount;
import com.gamerin.backend.domain.report.entity.ReportReasonCode;
import com.gamerin.backend.domain.report.entity.ReportStatus;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.repository.ReportCountRepository;
import com.gamerin.backend.domain.report.repository.ReportRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ReportService {

    private final ReportRepository reportRepository;
    private final ReportCountRepository reportCountRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final PostCommentRepository postCommentRepository;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final MentoringApplicationRepository mentoringApplicationRepository;
    private final DirectMessageRepository directMessageRepository;
    private final ReportCountInitializer reportCountInitializer;
    private final UserPenaltyService userPenaltyService;

    public ReportService(
            ReportRepository reportRepository,
            ReportCountRepository reportCountRepository,
            SystemConfigRepository systemConfigRepository,
            UserRepository userRepository,
            PostRepository postRepository,
            PostCommentRepository postCommentRepository,
            AdminAuditLogRepository adminAuditLogRepository,
            MentoringApplicationRepository mentoringApplicationRepository,
            DirectMessageRepository directMessageRepository,
            ReportCountInitializer reportCountInitializer,
            UserPenaltyService userPenaltyService) {
        this.reportRepository = reportRepository;
        this.reportCountRepository = reportCountRepository;
        this.systemConfigRepository = systemConfigRepository;
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.postCommentRepository = postCommentRepository;
        this.adminAuditLogRepository = adminAuditLogRepository;
        this.mentoringApplicationRepository = mentoringApplicationRepository;
        this.directMessageRepository = directMessageRepository;
        this.reportCountInitializer = reportCountInitializer;
        this.userPenaltyService = userPenaltyService;
    }

    // ================= [유저 신고 관련] =================

    /**
     * 신고 사유 목록 조회
     */
    public List<ReportReasonResponse> getReportReasons() {
        return Arrays.stream(ReportReasonCode.values())
                .map(ReportReasonResponse::from)
                .toList();
    }

    /**
     * 일반 유저 본인의 접수 신고 목록 페이징 조회
     */
    public Page<ReportResponse> getMyReports(CustomUserPrincipal principal, Pageable pageable) {
        return reportRepository.findByReporterIdOrderByCreatedAtDesc(principal.getUserId(), pageable)
                .map(ReportResponse::from);
    }

    /**
     * 통합 신고 접수 (검증 강화 + reportCode null 방지 + 동시성 제어 + 실제 콘텐츠 자동 숨김 처리)
     */
    @Transactional
    public ReportResponse createReport(CustomUserPrincipal principal, ReportCreateRequest request) {
        User reporter = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "신고자 유저 정보를 찾을 수 없습니다."));

        // 1. 존재하지 않거나 삭제된 대상 신고 검증 및 권한 확인 (404 예외)
        validateTargetExists(reporter.getId(), request.targetType(), request.targetId());

        // 2. 중복 신고 검증 (409 예외)
        boolean alreadyReported = reportRepository.existsByReporterIdAndTargetTypeAndTargetId(
                reporter.getId(),
                request.targetType(),
                request.targetId());
        if (alreadyReported) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 해당 콘텐츠/유저에 대해 신고를 접수하셨습니다.");
        }

        // 3. 신고 접수 시점 스냅샷 생성 (권한 검증된 데이터에서 생성)
        String targetSnippet = createTargetSnippet(reporter.getId(), request.targetType(), request.targetId());

        // 4. 신고 엔티티 생성 및 DB 저장
        Report report = Report.create(
                reporter,
                request.targetType(),
                request.targetId(),
                targetSnippet,
                request.reasonCode(),
                request.details());
        try {
            reportRepository.saveAndFlush(report);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 해당 콘텐츠/유저에 대해 신고를 접수하셨습니다.", e);
        }

        // reportCode DB 생성값 반영을 위해 재조회 (reportCode null 반환 방지)
        Report savedReport = reportRepository.findById(report.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "저장된 신고 정보를 찾을 수 없습니다."));

        // 5. 신고 카운트 증가 및 5회 이상 시 실제 콘텐츠 숨김 연동
        updateReportCountAndAutoHide(request.targetType(), request.targetId());

        return ReportResponse.from(savedReport);
    }

    // ================= [어드민 신고 관리] =================

    /**
     * 어드민 전용 신고 목록 검색 및 페이징 조회
     */
    public Page<ReportResponse> getAdminReports(ReportSearchCondition condition, Pageable pageable) {
        ReportStatus status = condition != null ? condition.status() : null;
        ReportTargetType targetType = condition != null ? condition.targetType() : null;
        ReportReasonCode reasonCode = condition != null ? condition.reasonCode() : null;
        String keyword = condition != null ? condition.keyword() : null;

        return reportRepository.searchReports(status, targetType, reasonCode, keyword, pageable)
                .map(ReportResponse::from);
    }

    /**
     * 어드민 전용 신고 상세 단건 조회 (기본 정보)
     */
    public ReportResponse getAdminReportById(UUID reportId) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "신고 내역을 찾을 수 없습니다. ID: " + reportId));
        return ReportResponse.from(report);
    }

    /**
     * 어드민 전용 신고 처리 상태 단순 변경 (담당 어드민 할당)
     */
    @Transactional
    public ReportResponse updateReportStatus(UUID reportId, ReportStatusUpdateRequest request,
            CustomUserPrincipal principal) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "신고 내역을 찾을 수 없습니다. ID: " + reportId));

        User admin = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "어드민 유저 정보를 찾을 수 없습니다."));

        ReportStatus previousStatus = report.getStatus();
        report.updateStatus(request.status(), admin);

        String actionType = switch (request.status()) {
            case IN_REVIEW -> "REPORT_IN_REVIEW";
            case RESOLVED -> "REPORT_RESOLVE";
            case REJECTED -> "REPORT_REJECT";
            case RECEIVED -> "REPORT_RECEIVE";
        };

        String logDetails = String.format("신고 상태 변경 (%s: %s -> %s, 담당 관리자: %s)",
                report.getReportCode(),
                previousStatus.getDescription(),
                request.status().getDescription(),
                admin.getNickname());

        adminAuditLogRepository.save(AdminAuditLog.create(
                admin,
                actionType,
                report.getTargetType(),
                report.getTargetId(),
                null,
                logDetails));

        return ReportResponse.from(report);
    }

    // ================= [어드민 신고 상세 및 원클릭 통합 판정] =================

    /**
     * 신고 코드(RPT-1001 등) 또는 UUID로 신고 상세 정보 조회 (프론트엔드 연동)
     */
    public AdminReportDetailResponse getAdminReportDetail(String reportIdOrCode) {
        Report report = findReportByIdOrCode(reportIdOrCode);

        // 1. 신고자 요약 정보
        AdminReportDetailResponse.ReportUserSummary reporterSummary = createReporterSummary(report.getReporter());

        // 2. 피신고자(대상자) 요약 정보 식별
        User targetUser = resolveTargetUser(report.getTargetType(), report.getTargetId());
        AdminReportDetailResponse.ReportUserSummary targetUserSummary = (targetUser != null)
                ? createReporterSummary(targetUser)
                : null;

        // 3. 콘텐츠 숨김 여부
        boolean isHidden = reportCountRepository
                .findByTargetTypeAndTargetId(report.getTargetType(), report.getTargetId())
                .map(ReportCount::isHidden)
                .orElse(false);

        return new AdminReportDetailResponse(
                ReportResponse.from(report),
                reporterSummary,
                targetUserSummary,
                isHidden);
    }

    /**
     * 어드민 신고 검토 시작 (상태 -> IN_REVIEW 및 관리자 할당)
     */
    @Transactional
    public AdminReportDetailResponse startReportReview(UUID adminId, String reportIdOrCode) {
        Report report = findReportByIdOrCode(reportIdOrCode);
        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "어드민 계정을 찾을 수 없습니다."));

        report.updateStatus(ReportStatus.IN_REVIEW, admin);
        reportRepository.save(report);

        adminAuditLogRepository.save(AdminAuditLog.create(
                admin,
                "REPORT_IN_REVIEW",
                report.getTargetType(),
                report.getTargetId(),
                null,
                String.format("신고 검토 시작 (%s, 담당 관리자: %s)", report.getReportCode(), admin.getNickname())));

        return getAdminReportDetail(reportIdOrCode);
    }

    /**
     * 어드민 신고 원클릭 통합 판정 처리 (상태 변경 + 콘텐츠 숨김 + 유저 제재 + 감사 로그)
     */
    @Transactional
    public AdminReportDetailResponse resolveReport(UUID adminId, String reportIdOrCode,
            AdminReportResolutionRequest request) {

        // [변경] 일반 조회 → 비관적 쓰기 락 조회로 교체 (동시 요청 중복 처리 방지)
        Report report;
        try {
            UUID reportId = UUID.fromString(reportIdOrCode);
            report = reportRepository.findByIdForUpdate(reportId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "신고 건을 찾을 수 없습니다."));
        } catch (IllegalArgumentException e) {
            // UUID 형식이 아니면 신고 코드로 조회
            report = reportRepository.findByReportCodeForUpdate(reportIdOrCode)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "신고 코드를 찾을 수 없습니다: " + reportIdOrCode));
        }

        // 이미 종결된 신고 중복 처리 방어 (락 획득 후 상태 재확인)
        if (report.getStatus() == ReportStatus.RESOLVED || report.getStatus() == ReportStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리 완료되었거나 반려된 신고입니다.");
        }

        // 허용된 판정 상태 검증
        if (request.decision() != ReportStatus.RESOLVED && request.decision() != ReportStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "처리 결정은 RESOLVED(처리 완료) 또는 REJECTED(반려)만 가능합니다.");
        }

        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "어드민 계정을 찾을 수 없습니다."));

        // 1. 신고 상태 변경
        report.updateStatus(request.decision(), admin);
        reportRepository.save(report);

        // 2. 콘텐츠 숨김 처리 (처리 완료이고 숨김 체크된 경우)
        if (request.decision() == ReportStatus.RESOLVED && request.hideTargetContent()
                && isAutoHideSupported(report.getTargetType())) {
            boolean hidden = hideTargetContent(report.getTargetType(), report.getTargetId());
            if (hidden) {
                ReportCount reportCount = getOrCreateReportCount(report.getTargetType(), report.getTargetId());
                reportCount.hide();
                reportCountRepository.save(reportCount);

                adminAuditLogRepository.save(AdminAuditLog.create(
                        admin,
                        "CONTENT_HIDE",
                        report.getTargetType(),
                        report.getTargetId(),
                        null,
                        String.format("신고 처리에 따른 콘텐츠 관리자 숨김 (%s, ID: %s)", report.getTargetType(),
                                report.getTargetId())));
            }
        }

        // 3. 피신고자 제재 부여 (처리 완료이고 제재 유형이 선택된 경우)
        if (request.decision() == ReportStatus.RESOLVED && request.penaltyType() != null) {
            User targetUser = resolveTargetUser(report.getTargetType(), report.getTargetId());
            if (targetUser != null) {
                UserPenaltyCreateRequest penaltyRequest = new UserPenaltyCreateRequest(
                        request.penaltyType(),
                        request.reason(),
                        null,
                        report.getId());
                userPenaltyService.createPenalty(adminId, targetUser.getId(), penaltyRequest);
            }
        }

        // 4. 신고 처리 감사 로그 적재
        String actionType = (request.decision() == ReportStatus.RESOLVED) ? "REPORT_RESOLVE" : "REPORT_REJECT";
        String logDetails = String.format("신고 판정 완료 (%s: %s, 사유: %s%s)",
                report.getReportCode(),
                request.decision().getDescription(),
                request.reason(),
                request.internalMemo() != null ? ", 내부메모: " + request.internalMemo() : "");

        adminAuditLogRepository.save(AdminAuditLog.create(
                admin,
                actionType,
                report.getTargetType(),
                report.getTargetId(),
                null,
                logDetails));

        return getAdminReportDetail(reportIdOrCode);
    }

    // ================= [어드민 숨김 콘텐츠 관리] =================

    /**
     * 임계값 초과로 자동 숨김 처리된 콘텐츠 목록 조회
     */
    public Page<HiddenContentResponse> getHiddenContents(Pageable pageable) {
        return reportCountRepository.findByIsHiddenTrue(pageable)
                .map(HiddenContentResponse::from);
    }

    @Transactional
    public HiddenContentResponse restoreHiddenContent(ReportTargetType targetType, UUID targetId, UUID adminId) {
        if (!isAutoHideSupported(targetType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "게시글 및 댓글 콘텐츠만 복구할 수 있습니다.");
        }

        ReportCount reportCount = reportCountRepository.findByTargetTypeAndTargetId(targetType, targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 콘텐츠의 신고 카운트 정보를 찾을 수 없습니다."));

        if (!reportCount.isHidden()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "숨김 처리된 콘텐츠만 복구할 수 있습니다.");
        }

        boolean successfullyRestored = restoreTargetContent(targetType, targetId);
        if (!successfullyRestored) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "복구 대상 원본 콘텐츠를 찾을 수 없거나 이미 영구 삭제되었습니다.");
        }

        reportCount.restore();
        ReportCount updated = reportCountRepository.save(reportCount);

        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "어드민 유저 정보를 찾을 수 없습니다."));

        adminAuditLogRepository.save(AdminAuditLog.create(
                admin,
                "CONTENT_RESTORE",
                targetType,
                targetId,
                null,
                String.format("자동 숨김 콘텐츠 관리자 수동 복구 (%s, ID: %s)", targetType.getDescription(), targetId)));

        return HiddenContentResponse.from(updated);
    }

    // ================= [내부 헬퍼 메서드] =================

    private Report findReportByIdOrCode(String reportIdOrCode) {
        try {
            UUID id = UUID.fromString(reportIdOrCode);
            return reportRepository.findById(id)
                    .orElseGet(() -> reportRepository.findByReportCode(reportIdOrCode)
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                    "신고 내역을 찾을 수 없습니다: " + reportIdOrCode)));
        } catch (IllegalArgumentException e) {
            return reportRepository.findByReportCode(reportIdOrCode)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "신고 내역을 찾을 수 없습니다: " + reportIdOrCode));
        }
    }

    private AdminReportDetailResponse.ReportUserSummary createReporterSummary(User user) {
        long reportsCount = reportRepository.countByTargetTypeAndTargetId(ReportTargetType.USER, user.getId());
        List<UserPenaltyResponse> penalties = userPenaltyService.getUserPenalties(user.getId(), PageRequest.of(0, 5))
                .getContent();
        String activeSanction = penalties.stream()
                .filter(UserPenaltyResponse::isActive)
                .findFirst()
                .map(p -> p.penaltyType().getDescription())
                .orElse("없음");

        return new AdminReportDetailResponse.ReportUserSummary(
                user.getId(),
                user.getNickname(),
                user.getHandle(),
                user.getCreatedAt(),
                reportsCount,
                activeSanction);
    }

    private User resolveTargetUser(ReportTargetType targetType, UUID targetId) {
        return switch (targetType) {
            case USER -> userRepository.findById(targetId).orElse(null);
            case POST -> postRepository.findById(targetId).map(Post::getAuthor).orElse(null);
            case COMMENT -> postCommentRepository.findById(targetId).map(PostComment::getAuthor).orElse(null);
            case MESSAGE -> directMessageRepository.findById(targetId).map(DirectMessage::getSender).orElse(null);
            case MENTORING -> mentoringApplicationRepository.findById(targetId)
                    .map(app -> (app.getProgram() != null && app.getProgram().getMentor() != null)
                            ? app.getProgram().getMentor().getUser()
                            : app.getMentee())
                    .orElse(null);
        };
    }

    /**
     * 신고 대상 실제 존재 여부 검증
     */
    private void validateTargetExists(UUID reporterId, ReportTargetType targetType, UUID targetId) {
        boolean exists = switch (targetType) {
            case POST -> postRepository.findByIdAndDeletedAtIsNull(targetId).isPresent();
            case COMMENT -> postCommentRepository.findById(targetId)
                    .map(comment -> comment.getDeletedAt() == null)
                    .orElse(false);
            case USER -> userRepository.findByIdAndDeletedAtIsNull(targetId).isPresent();
            case MENTORING -> mentoringApplicationRepository.existsById(targetId);
            case MESSAGE ->
                directMessageRepository.findActiveByIdAndParticipantUserId(targetId, reporterId).isPresent();
        };

        if (!exists) {
            String message = targetType == ReportTargetType.MESSAGE
                    ? "신고 대상 메시지이(가) 존재하지 않거나 접근 권한이 없습니다."
                    : "신고 대상 " + targetType.getDescription() + "이(가) 존재하지 않거나 이미 삭제되었습니다.";
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        }
    }

    /**
     * 신고 대상 원본 스냅샷 생성
     */
    private String createTargetSnippet(UUID reporterId, ReportTargetType targetType, UUID targetId) {
        return switch (targetType) {
            case POST -> postRepository.findById(targetId)
                    .map(Post::getContent)
                    .orElse("게시글 (ID: " + targetId + ")");
            case COMMENT -> postCommentRepository.findById(targetId)
                    .map(PostComment::getContent)
                    .orElse("댓글 (ID: " + targetId + ")");
            case USER -> userRepository.findById(targetId)
                    .map(user -> "닉네임: " + user.getNickname() + " (@" + user.getHandle() + ")")
                    .orElse("유저 (ID: " + targetId + ")");
            case MESSAGE -> directMessageRepository.findActiveByIdAndParticipantUserId(targetId, reporterId)
                    .map(DirectMessage::getContent)
                    .orElse("메시지 (ID: " + targetId + ")");
            case MENTORING -> mentoringApplicationRepository.findById(targetId)
                    .map(app -> "멘토링 신청 (프로그램: " + (app.getProgram() != null ? app.getProgram().getTitle() : "") + ")")
                    .orElse("멘토링 신청 건 (ID: " + targetId + ")");
        };
    }

    /**
     * 비관적 락 및 예외 핸들링을 적용한 신고 카운터 갱신 및 실제 콘텐츠 자동 숨김
     */
    private void updateReportCountAndAutoHide(ReportTargetType targetType, UUID targetId) {
        ReportCount reportCount = getOrCreateReportCount(targetType, targetId);
        reportCount.incrementCount();

        long threshold = systemConfigRepository.findByConfigKey("AUTO_HIDE_THRESHOLD")
                .map(config -> {
                    try {
                        return Long.parseLong(config.getConfigValue().trim());
                    } catch (NumberFormatException e) {
                        return 5L; // 파싱 실패 시 기본값 5로 안전하게 대체
                    }
                })
                .orElse(5L);

        boolean autoHideEnabled = systemConfigRepository.findByConfigKey("AUTO_HIDE_ENABLED")
                .map(config -> Boolean.parseBoolean(config.getConfigValue()))
                .orElse(true);

        if (autoHideEnabled && isAutoHideSupported(targetType) && reportCount.getReportCount() >= threshold
                && !reportCount.isHidden()) {
            boolean successfullyHidden = hideTargetContent(targetType, targetId);
            if (successfullyHidden) {
                reportCount.hide();
            }
        }

        reportCountRepository.save(reportCount);
    }

    /**
     * 자동 숨김 및 복구를 지원하는 콘텐츠 대상 유형인지 확인 (게시글, 댓글)
     */
    private boolean isAutoHideSupported(ReportTargetType targetType) {
        return targetType == ReportTargetType.POST || targetType == ReportTargetType.COMMENT;
    }

    /**
     * 독립 트랜잭션 초기화와 비관적 락을 결합하여 트랜잭션 중단 없이 안전하게 ReportCount 조회/생성
     */
    private ReportCount getOrCreateReportCount(ReportTargetType targetType, UUID targetId) {
        reportCountInitializer.initIfNotExists(targetType, targetId);

        return reportCountRepository.findByTargetTypeAndTargetId(targetType, targetId)
                .orElseThrow(() -> new IllegalStateException("신고 카운트 레코드를 초기화할 수 없습니다."));
    }

    /**
     * 실제 대상 콘텐츠 숨김(소프트 삭제) 수행
     */
    private boolean hideTargetContent(ReportTargetType targetType, UUID targetId) {
        if (targetType == ReportTargetType.POST) {
            return postRepository.findByIdAndDeletedAtIsNull(targetId)
                    .map(post -> {
                        post.softDelete();
                        return true;
                    }).orElse(false);
        } else if (targetType == ReportTargetType.COMMENT) {
            return postCommentRepository.findById(targetId)
                    .filter(c -> c.getDeletedAt() == null)
                    .map(comment -> {
                        comment.softDelete();
                        return true;
                    }).orElse(false);
        }
        return false;
    }

    /**
     * 실제 대상 콘텐츠 복구 수행 (원본 엔티티 존재 확인 및 소프트 삭제 복원)
     */
    private boolean restoreTargetContent(ReportTargetType targetType, UUID targetId) {
        if (targetType == ReportTargetType.POST) {
            return postRepository.findById(targetId)
                    .map(post -> {
                        if (post.getDeletedAt() != null) {
                            post.restore();
                        }
                        return true;
                    }).orElse(false);
        } else if (targetType == ReportTargetType.COMMENT) {
            return postCommentRepository.findById(targetId)
                    .map(comment -> {
                        if (comment.getDeletedAt() != null) {
                            comment.restore();
                        }
                        return true;
                    }).orElse(false);
        }
        return false;
    }
}