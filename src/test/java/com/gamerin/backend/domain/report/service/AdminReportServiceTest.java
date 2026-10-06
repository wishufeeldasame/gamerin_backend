package com.gamerin.backend.domain.report.service;

import com.gamerin.backend.domain.admin.entity.AdminAuditLog;
import com.gamerin.backend.domain.admin.repository.AdminAuditLogRepository;
import com.gamerin.backend.domain.admin.repository.SystemConfigRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.message.repository.DirectMessageRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.report.dto.request.AdminReportResolutionRequest;
import com.gamerin.backend.domain.report.dto.request.UserPenaltyCreateRequest;
import com.gamerin.backend.domain.report.dto.response.AdminReportDetailResponse;
import com.gamerin.backend.domain.report.entity.PenaltyType;
import com.gamerin.backend.domain.report.entity.Report;
import com.gamerin.backend.domain.report.entity.ReportCount;
import com.gamerin.backend.domain.report.entity.ReportReasonCode;
import com.gamerin.backend.domain.report.entity.ReportStatus;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.repository.ReportCountRepository;
import com.gamerin.backend.domain.report.repository.ReportRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminReportServiceTest {

    @Mock
    private ReportRepository reportRepository;
    @Mock
    private ReportCountRepository reportCountRepository;
    @Mock
    private SystemConfigRepository systemConfigRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PostRepository postRepository;
    @Mock
    private PostCommentRepository postCommentRepository;
    @Mock
    private AdminAuditLogRepository adminAuditLogRepository;
    @Mock
    private MentoringApplicationRepository mentoringApplicationRepository;
    @Mock
    private DirectMessageRepository directMessageRepository;
    @Mock
    private ReportCountInitializer reportCountInitializer;
    @Mock
    private UserPenaltyService userPenaltyService;

    @InjectMocks
    private ReportService reportService;

    private User admin;
    private User reporter;
    private User targetUser;
    private Post post;
    private Report report;
    private ReportCount reportCount;

    private UUID adminId;
    private UUID reporterId;
    private UUID targetUserId;
    private UUID postId;
    private UUID reportId;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        reporterId = UUID.randomUUID();
        targetUserId = UUID.randomUUID();
        postId = UUID.randomUUID();
        reportId = UUID.randomUUID();

        // 1. 유저 픽스처
        admin = User.createLocal("admin@gamerin.com", "admin01", "어드민", "hash");
        ReflectionTestUtils.setField(admin, "id", adminId);
        ReflectionTestUtils.setField(admin, "createdAt", OffsetDateTime.now());

        reporter = User.createLocal("reporter@gamerin.com", "reporter01", "신고자", "hash");
        ReflectionTestUtils.setField(reporter, "id", reporterId);
        ReflectionTestUtils.setField(reporter, "createdAt", OffsetDateTime.now().minusDays(30));

        targetUser = User.createLocal("baduser@gamerin.com", "baduser01", "피신고자", "hash");
        ReflectionTestUtils.setField(targetUser, "id", targetUserId);
        ReflectionTestUtils.setField(targetUser, "createdAt", OffsetDateTime.now().minusDays(10));

        // 2. 신고 대상 게시글 픽스처
        post = Post.create(targetUser, "신고 대상 게시글 본문입니다.");
        ReflectionTestUtils.setField(post, "id", postId);

        // 3. 신고 건 픽스처
        report = Report.create(
                reporter,
                ReportTargetType.POST,
                postId,
                "스니펫: 신고 대상 게시글 본문입니다.",
                ReportReasonCode.PROFANITY,
                "지속적인 욕설과 비방 내용이 있습니다.");
        ReflectionTestUtils.setField(report, "id", reportId);
        ReflectionTestUtils.setField(report, "reportCode", "RPT-1001");
        ReflectionTestUtils.setField(report, "createdAt", OffsetDateTime.now());
        ReflectionTestUtils.setField(report, "updatedAt", OffsetDateTime.now());

        // 4. 신고 카운트 픽스처
        reportCount = ReportCount.create(ReportTargetType.POST, postId);
        ReflectionTestUtils.setField(reportCount, "id", UUID.randomUUID());
    }

    private void mockCommonReportDetailDependencies() {
        // 피신고자 식별 (POST -> Author)
        given(postRepository.findById(postId)).willReturn(Optional.of(post));

        // 신고자/피신고자 요약 정보 조회용 목
        given(reportRepository.countByTargetTypeAndTargetId(eq(ReportTargetType.USER), any(UUID.class)))
                .willReturn(0L);
        given(userPenaltyService.getUserPenalties(any(UUID.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        // 콘텐츠 숨김 여부
        given(reportCountRepository.findByTargetTypeAndTargetId(ReportTargetType.POST, postId))
                .willReturn(Optional.of(reportCount));
    }

    @Test
    @DisplayName("신고 UUID로 상세 조회 시 신고자/피신고자 요약 정보와 콘텐츠 숨김 여부가 정상 결합된다")
    void getAdminReportDetail_success_byUuid() {
        // given
        given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
        mockCommonReportDetailDependencies();

        // when
        AdminReportDetailResponse response = reportService.getAdminReportDetail(reportId.toString());

        // then
        assertThat(response).isNotNull();
        assertThat(response.report().id()).isEqualTo(reportId);
        assertThat(response.report().reportCode()).isEqualTo("RPT-1001");

        // 신고자 요약
        assertThat(response.reporter()).isNotNull();
        assertThat(response.reporter().handle()).isEqualTo("reporter01");
        assertThat(response.reporter().nickname()).isEqualTo("신고자");

        // 피신고자 요약 (게시글 작성자)
        assertThat(response.targetUser()).isNotNull();
        assertThat(response.targetUser().handle()).isEqualTo("baduser01");
        assertThat(response.targetUser().nickname()).isEqualTo("피신고자");

        // 숨김 여부 기본값
        assertThat(response.contentHidden()).isFalse();
    }

    @Test
    @DisplayName("신고 코드(RPT-1001)로 상세 조회가 정상 수행된다")
    void getAdminReportDetail_success_byReportCode() {
        // given
        given(reportRepository.findByReportCode("RPT-1001")).willReturn(Optional.of(report));
        mockCommonReportDetailDependencies();

        // when
        AdminReportDetailResponse response = reportService.getAdminReportDetail("RPT-1001");

        // then
        assertThat(response).isNotNull();
        assertThat(response.report().reportCode()).isEqualTo("RPT-1001");
    }

    @Test
    @DisplayName("신고 검토 시작 시 상태가 IN_REVIEW로 변경되고 담당 관리자가 할당되며 감사 로그가 적재된다")
    void startReportReview_success() {
        // given
        given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        mockCommonReportDetailDependencies();

        // when
        AdminReportDetailResponse response = reportService.startReportReview(adminId, reportId.toString());

        // then
        assertThat(report.getStatus()).isEqualTo(ReportStatus.IN_REVIEW);
        assertThat(report.getAssignedAdmin()).isEqualTo(admin);

        verify(reportRepository).save(report);
        verify(adminAuditLogRepository).save(any(AdminAuditLog.class));
        assertThat(response.report().status()).isEqualTo(ReportStatus.IN_REVIEW);
    }

    @Test
        @DisplayName("원클릭 판정(RESOLVED) 시 상태 변경, 콘텐츠 숨김(소프트삭제), 피신고자 제재, 감사 로그가 일괄 처리된다")
        void resolveReport_resolved_withSanctionAndContentHide() {
            // given
            AdminReportResolutionRequest request = new AdminReportResolutionRequest(
                    ReportStatus.RESOLVED,
                    true, // hideTargetContent
                    PenaltyType.SUSPENSION_7D, // 제재 7일 정지
                    "반복적인 욕설 및 비방 행위 확인",
                    "1차 7일 정지 부여함");

    
            given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(report));
            given(reportRepository.findById(reportId)).willReturn(Optional.of(report)); // getAdminReportDetail 재조회용
            given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
            given(postRepository.findByIdAndDeletedAtIsNull(postId)).willReturn(Optional.of(post));
            mockCommonReportDetailDependencies();

    
            // when
            AdminReportDetailResponse response = reportService.resolveReport(adminId, reportId.toString(),request);
    
            // then
            // 1. 신고 상태 변경
            assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
            verify(reportRepository).save(report);

            // 2. 콘텐츠 숨김 및 소프트 삭제
            assertThat(post.getDeletedAt()).isNotNull();
            assertThat(reportCount.isHidden()).isTrue();
            verify(reportCountRepository).save(reportCount);

            // 3. 제재 생성 호출 검증
            verify(userPenaltyService).createPenalty(eq(adminId), eq(targetUserId), any(UserPenaltyCreateRequest.class));

            // 4. 감사 로그 적재 검증 (콘텐츠 숨김 로그 1건 + 신고 판정 완료 로그 1건)
            verify(adminAuditLogRepository, atLeast(2)).save(any(AdminAuditLog.class));
        }

    @Test
    @DisplayName("원클릭 판정(REJECTED) 시 제재나 숨김 처리 없이 상태만 기각으로 변경된다")
    void resolveReport_rejected_noSanction() {
        // given
        AdminReportResolutionRequest request = new AdminReportResolutionRequest(
                ReportStatus.REJECTED,
                false,
                null,
                "정상적인 게임 공략 게시글로 확인됨",
                null);


        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(report));
        given(reportRepository.findById(reportId)).willReturn(Optional.of(report)); // getAdminReportDetail 재조회용
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        mockCommonReportDetailDependencies();


        // when
        AdminReportDetailResponse response = reportService.resolveReport(adminId, reportId.toString(),
                request);

        // then
        assertThat(report.getStatus()).isEqualTo(ReportStatus.REJECTED);
        verify(reportRepository).save(report);

        // 제재 부과 및 콘텐츠 숨김은 호출되지 않아야 함
        verify(userPenaltyService, never()).createPenalty(any(), any(), any());
        verify(postRepository, never()).findByIdAndDeletedAtIsNull(any());

        // 판정 감사 로그 적재
        verify(adminAuditLogRepository).save(any(AdminAuditLog.class));
    }

    @Test
    @DisplayName("존재하지 않는 신고 ID/코드 조회 시 404 예외가 발생한다")
    void getAdminReportDetail_notFound() {
        // given
        given(reportRepository.findByReportCode("NON-EXISTENT")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reportService.getAdminReportDetail("NON-EXISTENT"))
                .isInstanceOf(ResponseStatusException.class);
    }
}