// 신고/어드민 시스템 통합 서비스 (유저 신고 접수 + 어드민 관리 및 숨김 콘텐츠 실시간 연동 복구)
package com.gamerin.backend.domain.report.service;

import com.gamerin.backend.domain.admin.repository.SystemConfigRepository;
import com.gamerin.backend.domain.admin.entity.AdminAuditLog;
import com.gamerin.backend.domain.admin.repository.AdminAuditLogRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.report.dto.request.ReportCreateRequest;
import com.gamerin.backend.domain.report.dto.request.ReportSearchCondition;
import com.gamerin.backend.domain.report.dto.request.ReportStatusUpdateRequest;
import com.gamerin.backend.domain.report.dto.response.HiddenContentResponse;
import com.gamerin.backend.domain.report.dto.response.ReportReasonResponse;
import com.gamerin.backend.domain.report.dto.response.ReportResponse;
import com.gamerin.backend.domain.report.entity.Report;
import com.gamerin.backend.domain.report.entity.ReportCount;
import com.gamerin.backend.domain.report.entity.ReportReasonCode;
import com.gamerin.backend.domain.report.entity.ReportStatus;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.repository.ReportCountRepository;
import com.gamerin.backend.domain.report.repository.ReportRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.message.entity.DirectMessage;
import com.gamerin.backend.domain.message.repository.DirectMessageRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
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
            ReportCountInitializer reportCountInitializer) {
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

    // ================= [어드민 신고 관리 - 4단계] =================

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
     * 어드민 전용 신고 처리 상태 변경 (담당 어드민 할당)
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

        // 변경된 상태에 따른 감사 로그 액션 타입 매핑
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

        // 동일 트랜잭션 내에서 관리자 감사 로그 적재
        adminAuditLogRepository.save(AdminAuditLog.create(
                admin,
                actionType,
                report.getTargetType(),
                report.getTargetId(),
                null,
                logDetails));

        return ReportResponse.from(report);
    }

    // ================= [어드민 숨김 콘텐츠 관리 - 4단계] =================

    /**
     * 임계값 초과로 자동 숨김 처리된 콘텐츠 목록 조회
     */
    public Page<HiddenContentResponse> getHiddenContents(Pageable pageable) {
        return reportCountRepository.findByIsHiddenTrue(pageable)
                .map(HiddenContentResponse::from);
    }

    @Transactional
    public HiddenContentResponse restoreHiddenContent(ReportTargetType targetType, UUID targetId, UUID adminId) {
        // 게시글/댓글 등 실제 자동 숨김을 지원하는 콘텐츠 유형만 복구 허용
        if (!isAutoHideSupported(targetType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "게시글 및 댓글 콘텐츠만 복구할 수 있습니다.");
        }

        ReportCount reportCount = reportCountRepository.findByTargetTypeAndTargetId(targetType, targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 콘텐츠의 신고 카운트 정보를 찾을 수 없습니다."));

        // 숨김 처리된 콘텐츠만 복구 허용 (중복 복구 및 미숨김 콘텐츠 임의 복구 차단)
        if (!reportCount.isHidden()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "숨김 처리된 콘텐츠만 복구할 수 있습니다.");
        }

        reportCount.restore();
        ReportCount updated = reportCountRepository.save(reportCount);

        // 실제 게시글/댓글 엔티티의 숨김 해제(복구) 반영
        restoreTargetContent(targetType, targetId);

        // 복구를 집행한 관리자 정보 조회 및 감사 로그(CONTENT_RESTORE) 적재
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
            // 메시지(DM)는 본인이 참여 중인 대화방의 메시지만 신고 가능 (제3자의 사적 메시지 탈취 방어)
            case MESSAGE -> directMessageRepository.findActiveByIdAndParticipantUserId(targetId, reporterId).isPresent();
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
                .map(config -> Long.parseLong(config.getConfigValue()))
                .orElse(5L);

        boolean autoHideEnabled = systemConfigRepository.findByConfigKey("AUTO_HIDE_ENABLED")
                .map(config -> Boolean.parseBoolean(config.getConfigValue()))
                .orElse(true);

        // 실제 활성 상태(deletedAt == null)인 콘텐츠를 시스템이 성공적으로 숨긴 경우에만 isHidden=true 설정
        // (작성자가 이미 직접 삭제한 콘텐츠가 자동 숨김 목록에 들어가서 오복구되는 현상 방지)
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
        // 1. 레코드가 없으면 독립 트랜잭션(REQUIRES_NEW)으로 안전하게 생성 시도 (충돌 발생 시에도 메인 트랜잭션 보호)
        reportCountInitializer.initIfNotExists(targetType, targetId);

        // 2. 비관적 쓰기 락(FOR UPDATE)으로 행 잠금을 획득하고 최신 상태 조회
        return reportCountRepository.findByTargetTypeAndTargetId(targetType, targetId)
                .orElseThrow(() -> new IllegalStateException("신고 카운트 레코드를 초기화할 수 없습니다."));
    }

    /**
     * 실제 대상 콘텐츠 숨김(소프트 삭제) 수행
     * 
     * @return 실제 활성 콘텐츠를 시스템이 소프트 삭제했으면 true, 이미 삭제된 상태였으면
     *         false
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
     * 실제 대상 콘텐츠 복구 수행 (삭제 상태인 콘텐츠만 안전하게 복원)
     */
    private void restoreTargetContent(ReportTargetType targetType, UUID targetId) {
        if (targetType == ReportTargetType.POST) {
            postRepository.findById(targetId)
                    .filter(post -> post.getDeletedAt() != null)
                    .ifPresent(Post::restore);
        } else if (targetType == ReportTargetType.COMMENT) {
            postCommentRepository.findById(targetId)
                    .filter(comment -> comment.getDeletedAt() != null)
                    .ifPresent(PostComment::restore);
        }
    }
}