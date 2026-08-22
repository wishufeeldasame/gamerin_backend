// 신고/어드민 시스템 통합 서비스 (유저 신고 접수 + 어드민 관리 및 숨김 콘텐츠 실시간 연동 복구)
package com.gamerin.backend.domain.report.service;

import com.gamerin.backend.domain.admin.repository.SystemConfigRepository;
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

    public ReportService(
            ReportRepository reportRepository,
            ReportCountRepository reportCountRepository,
            SystemConfigRepository systemConfigRepository,
            UserRepository userRepository,
            PostRepository postRepository,
            PostCommentRepository postCommentRepository
    ) {
        this.reportRepository = reportRepository;
        this.reportCountRepository = reportCountRepository;
        this.systemConfigRepository = systemConfigRepository;
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.postCommentRepository = postCommentRepository;
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

        // 1. 존재하지 않거나 삭제된 대상 신고 검증 (404 예외)
        validateTargetExists(request.targetType(), request.targetId());

        // 2. 중복 신고 검증 (409 예외)
        boolean alreadyReported = reportRepository.existsByReporterIdAndTargetTypeAndTargetId(
                reporter.getId(),
                request.targetType(),
                request.targetId()
        );
        if (alreadyReported) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 해당 콘텐츠/유저에 대해 신고를 접수하셨습니다.");
        }

        // 3. 신고 접수 시점 스냅샷 생성
        String targetSnippet = createTargetSnippet(request.targetType(), request.targetId());

        // 4. 신고 엔티티 생성 및 DB 저장
        Report report = Report.create(
                reporter,
                request.targetType(),
                request.targetId(),
                targetSnippet,
                request.reasonCode(),
                request.details()
        );
        reportRepository.saveAndFlush(report);

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
    public ReportResponse updateReportStatus(UUID reportId, ReportStatusUpdateRequest request, CustomUserPrincipal principal) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "신고 내역을 찾을 수 없습니다. ID: " + reportId));

        User admin = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "어드민 유저 정보를 찾을 수 없습니다."));

        report.updateStatus(request.status(), admin);
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

    /**
     * 자동 숨김 처리된 콘텐츠 복구 (report_counts.is_hidden = false + 실제 게시글/댓글 복구)
     */
    @Transactional
    public HiddenContentResponse restoreHiddenContent(ReportTargetType targetType, UUID targetId) {
        ReportCount reportCount = reportCountRepository.findByTargetTypeAndTargetId(targetType, targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 콘텐츠의 신고 카운트 정보를 찾을 수 없습니다."));

        reportCount.restore();
        ReportCount updated = reportCountRepository.save(reportCount);

        // 실제 게시글/댓글 엔티티의 숨김 해제(복구) 반영
        restoreTargetContent(targetType, targetId);

        return HiddenContentResponse.from(updated);
    }

    // ================= [내부 헬퍼 메서드] =================

    /**
     * 신고 대상 실제 존재 여부 검증
     */
    private void validateTargetExists(ReportTargetType targetType, UUID targetId) {
        boolean exists = switch (targetType) {
            case POST -> postRepository.findByIdAndDeletedAtIsNull(targetId).isPresent();
            case COMMENT -> postCommentRepository.findById(targetId)
                    .map(comment -> comment.getDeletedAt() == null)
                    .orElse(false);
            case USER -> userRepository.findById(targetId)
                    .map(User::isActive)
                    .orElse(false);
            case MENTORING, MESSAGE -> true;
        };

        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "신고 대상 " + targetType.getDescription() + "이(가) 존재하지 않거나 이미 삭제되었습니다.");
        }
    }

    /**
     * 신고 대상 원본 스냅샷 생성
     */
    private String createTargetSnippet(ReportTargetType targetType, UUID targetId) {
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
            default -> targetType.name() + " (ID: " + targetId + ")";
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

        if (autoHideEnabled && reportCount.getReportCount() >= threshold && !reportCount.isHidden()) {
            reportCount.hide();
            // 실제 콘텐츠(게시글/댓글) 소프트 삭제(숨김) 반영
            hideTargetContent(targetType, targetId);
        }

        reportCountRepository.save(reportCount);
    }

    /**
     * 동시 생성 충돌(Race Condition)을 안전하게 방어하는 ReportCount 조회/생성
     */
    private ReportCount getOrCreateReportCount(ReportTargetType targetType, UUID targetId) {
        return reportCountRepository.findByTargetTypeAndTargetId(targetType, targetId)
                .orElseGet(() -> {
                    try {
                        return reportCountRepository.saveAndFlush(ReportCount.create(targetType, targetId));
                    } catch (DataIntegrityViolationException e) {
                        return reportCountRepository.findByTargetTypeAndTargetId(targetType, targetId)
                                .orElseThrow(() -> e);
                    }
                });
    }

    /**
     * 실제 대상 콘텐츠 숨김(소프트 삭제) 수행
     */
    private void hideTargetContent(ReportTargetType targetType, UUID targetId) {
        if (targetType == ReportTargetType.POST) {
            postRepository.findByIdAndDeletedAtIsNull(targetId).ifPresent(Post::softDelete);
        } else if (targetType == ReportTargetType.COMMENT) {
            postCommentRepository.findById(targetId)
                    .filter(c -> c.getDeletedAt() == null)
                    .ifPresent(PostComment::softDelete);
        }
    }

    /**
     * 실제 대상 콘텐츠 복구 수행
     */
    private void restoreTargetContent(ReportTargetType targetType, UUID targetId) {
        if (targetType == ReportTargetType.POST) {
            postRepository.findById(targetId).ifPresent(Post::restore);
        } else if (targetType == ReportTargetType.COMMENT) {
            postCommentRepository.findById(targetId).ifPresent(PostComment::restore);
        }
    }
}