package com.gamerin.backend.domain.report.service;

import com.gamerin.backend.domain.admin.repository.SystemConfigRepository;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.report.dto.request.ReportCreateRequest;
import com.gamerin.backend.domain.report.dto.response.ReportReasonResponse;
import com.gamerin.backend.domain.report.dto.response.ReportResponse;
import com.gamerin.backend.domain.report.entity.Report;
import com.gamerin.backend.domain.report.entity.ReportCount;
import com.gamerin.backend.domain.report.entity.ReportReasonCode;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.repository.ReportCountRepository;
import com.gamerin.backend.domain.report.repository.ReportRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** 일반 유저용 신고 접수(중복 방지, 카운트 증가, 자동 숨김 처리) 및 신고 사유 목록 조회 비즈니스 로직 서비스 */
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

    /**
     * 신고 사유 목록 조회 (Dropdown 목록용)
     */
    public List<ReportReasonResponse> getReportReasons() {
        return Arrays.stream(ReportReasonCode.values())
                .map(ReportReasonResponse::from)
                .toList();
    }

    /**
     * 통합 신고 접수 (중복 신고 방지 + 스냅샷 생성 + 카운트 증가 + 임계값 초과 시 자동 숨김)
     */
    @Transactional
    public ReportResponse createReport(CustomUserPrincipal principal, ReportCreateRequest request) {
        User reporter = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "신고자 유저 정보를 찾을 수 없습니다."));

        // 1. 중복 신고 검증
        boolean alreadyReported = reportRepository.existsByReporterIdAndTargetTypeAndTargetId(
                reporter.getId(),
                request.targetType(),
                request.targetId()
        );
        if (alreadyReported) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 해당 콘텐츠/유저에 대해 신고를 접수하셨습니다.");
        }

        // 2. 신고 접수 시점의 대상 스냅샷(targetSnippet) 생성
        String targetSnippet = createTargetSnippet(request.targetType(), request.targetId());

        // 3. 신고 엔티티 생성 및 저장
        Report report = Report.create(
                reporter,
                request.targetType(),
                request.targetId(),
                targetSnippet,
                request.reasonCode(),
                request.details()
        );
        Report savedReport = reportRepository.save(report);

        // 4. 신고 누적 카운트 증가 및 자동 숨김 판단
        updateReportCountAndAutoHide(request.targetType(), request.targetId());

        return ReportResponse.from(savedReport);
    }

    /**
     * 신고 대상 유형별 원본 스냅샷 생성 (게시글/댓글/유저 내용 보존)
     */
    private String createTargetSnippet(ReportTargetType targetType, UUID targetId) {
        return switch (targetType) {
            case POST -> postRepository.findById(targetId)
                    .map(post -> post.getContent())
                    .orElse("게시글 정보 (ID: " + targetId + ")");
            case COMMENT -> postCommentRepository.findById(targetId)
                    .map(comment -> comment.getContent())
                    .orElse("댓글 정보 (ID: " + targetId + ")");
            case USER -> userRepository.findById(targetId)
                    .map(user -> "닉네임: " + user.getNickname() + " (@" + user.getHandle() + ")")
                    .orElse("유저 정보 (ID: " + targetId + ")");
            default -> targetType.name() + " (ID: " + targetId + ")";
        };
    }

    /**
     * 신고 카운트 +1 및 임계값 초과 자동 숨김 로직
     */
    private void updateReportCountAndAutoHide(ReportTargetType targetType, UUID targetId) {
        ReportCount reportCount = reportCountRepository.findByTargetTypeAndTargetId(targetType, targetId)
                .orElseGet(() -> ReportCount.create(targetType, targetId));

        reportCount.incrementCount();

        // system_configs 테이블에서 설정값 조회 (기본값: threshold = 5, enabled = true)
        long threshold = systemConfigRepository.findByConfigKey("AUTO_HIDE_THRESHOLD")
                .map(config -> Long.parseLong(config.getConfigValue()))
                .orElse(5L);

        boolean autoHideEnabled = systemConfigRepository.findByConfigKey("AUTO_HIDE_ENABLED")
                .map(config -> Boolean.parseBoolean(config.getConfigValue()))
                .orElse(true);

        if (autoHideEnabled && reportCount.getReportCount() >= threshold) {
            reportCount.hide();
        }

        reportCountRepository.save(reportCount);
    }
}