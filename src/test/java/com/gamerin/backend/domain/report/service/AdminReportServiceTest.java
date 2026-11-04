package com.gamerin.backend.domain.report.service;

import com.gamerin.backend.domain.admin.entity.AdminAuditLog;
import com.gamerin.backend.domain.admin.repository.AdminAuditLogRepository;
import com.gamerin.backend.domain.admin.repository.SystemConfigRepository;
import com.gamerin.backend.domain.admin.service.AdminMentoringService;
import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.PaymentStatus;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.message.repository.DirectMessageRepository;
import com.gamerin.backend.domain.message.entity.DirectMessage;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.report.dto.request.AdminReportResolutionRequest;
import com.gamerin.backend.domain.report.dto.request.ReportCreateRequest;
import com.gamerin.backend.domain.report.dto.request.ReportStatusUpdateRequest;
import com.gamerin.backend.domain.report.dto.request.UserPenaltyCreateRequest;
import com.gamerin.backend.domain.report.dto.response.AdminReportDetailResponse;
import com.gamerin.backend.domain.report.dto.response.ReportResponse;
import com.gamerin.backend.domain.report.entity.PenaltyType;
import com.gamerin.backend.domain.report.entity.Report;
import com.gamerin.backend.domain.report.entity.ReportCount;
import com.gamerin.backend.domain.report.entity.ReportReasonCode;
import com.gamerin.backend.domain.report.entity.ReportStage;
import com.gamerin.backend.domain.report.entity.ReportStatus;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.repository.ReportCountRepository;
import com.gamerin.backend.domain.report.repository.ReportRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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
    private MentoringProgramRepository mentoringProgramRepository;
    @Mock
    private AdminMentoringService adminMentoringService;
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

        // [이슈 #76 반영] 행 잠금 없는 숨김 여부 확인 메서드 목 스터빙
        given(reportCountRepository.existsByTargetTypeAndTargetIdAndIsHiddenTrue(ReportTargetType.POST, postId))
                .willReturn(reportCount.isHidden());
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
        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(report));
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
        report.updateStatus(ReportStatus.IN_REVIEW, admin);
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

        // [추가] 쓰기 경로(콘텐츠 숨김)에서 호출되는 비관적 락 조회 mock
        given(reportCountRepository.findByTargetTypeAndTargetId(ReportTargetType.POST, postId))
                .willReturn(Optional.of(reportCount));

        mockCommonReportDetailDependencies();

        // when
        AdminReportDetailResponse response = reportService.resolveReport(adminId, reportId.toString(), request);

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
        report.updateStatus(ReportStatus.IN_REVIEW, admin);
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
        AdminReportDetailResponse response = reportService.resolveReport(adminId, reportId.toString(), request);

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

    // ================= [이슈 #135] 신고 상태 전이 제한 =================

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @ParameterizedTest
    @EnumSource(value = ReportStatus.class, names = {"RESOLVED", "REJECTED"})
    @DisplayName("종결된 신고는 상태 변경 API로 어떤 상태로도 되돌릴 수 없다 (409)")
    void updateReportStatus_terminalReport_conflict(ReportStatus terminal) {
        report.updateStatus(terminal, admin);
        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(report));

        for (ReportStatus next : ReportStatus.values()) {
            assertConflict(() -> reportService.updateReportStatus(
                    reportId, new ReportStatusUpdateRequest(next), CustomUserPrincipal.from(admin)));
        }

        assertThat(report.getStatus()).isEqualTo(terminal);
        verify(adminAuditLogRepository, never()).save(any(AdminAuditLog.class));
    }

    @ParameterizedTest
    @EnumSource(value = ReportStatus.class, names = {"RESOLVED", "REJECTED"})
    @DisplayName("상태 변경 API로는 사유 없이 신고를 종결할 수 없다 (409)")
    void updateReportStatus_toTerminal_conflict(ReportStatus terminal) {
        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(report));

        assertConflict(() -> reportService.updateReportStatus(
                reportId, new ReportStatusUpdateRequest(terminal), CustomUserPrincipal.from(admin)));

        assertThat(report.getStatus()).isEqualTo(ReportStatus.RECEIVED);
        verify(adminAuditLogRepository, never()).save(any(AdminAuditLog.class));
    }

    @Test
    @DisplayName("검토 중인 신고를 접수(RECEIVED) 상태로 되돌릴 수 없다 (409)")
    void updateReportStatus_inReviewToReceived_conflict() {
        report.updateStatus(ReportStatus.IN_REVIEW, admin);
        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(report));

        assertConflict(() -> reportService.updateReportStatus(
                reportId, new ReportStatusUpdateRequest(ReportStatus.RECEIVED), CustomUserPrincipal.from(admin)));

        assertThat(report.getStatus()).isEqualTo(ReportStatus.IN_REVIEW);
    }

    @Test
    @DisplayName("접수된 신고는 상태 변경 API로 IN_REVIEW 전환 및 담당 관리자 할당이 가능하다")
    void updateReportStatus_receivedToInReview_success() {
        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(report));
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));

        ReportResponse response = reportService.updateReportStatus(
                reportId, new ReportStatusUpdateRequest(ReportStatus.IN_REVIEW), CustomUserPrincipal.from(admin));

        assertThat(report.getStatus()).isEqualTo(ReportStatus.IN_REVIEW);
        assertThat(report.getAssignedAdmin()).isEqualTo(admin);
        assertThat(response.status()).isEqualTo(ReportStatus.IN_REVIEW);
        verify(adminAuditLogRepository).save(any(AdminAuditLog.class));
    }

    @ParameterizedTest
    @EnumSource(value = ReportStatus.class, names = {"RESOLVED", "REJECTED"})
    @DisplayName("종결된 신고는 검토 시작(start-review)으로 다시 열 수 없다 (409)")
    void startReportReview_terminalReport_conflict(ReportStatus terminal) {
        report.updateStatus(terminal, admin);
        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(report));

        assertConflict(() -> reportService.startReportReview(adminId, reportId.toString()));

        assertThat(report.getStatus()).isEqualTo(terminal);
        verify(reportRepository, never()).save(any(Report.class));
        verify(adminAuditLogRepository, never()).save(any(AdminAuditLog.class));
    }

    @Test
    @DisplayName("종결된 신고를 다시 판정(resolve)하면 409로 거부된다")
    void resolveReport_terminalReport_conflict() {
        report.updateStatus(ReportStatus.RESOLVED, admin);
        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(report));

        assertConflict(() -> reportService.resolveReport(adminId, reportId.toString(),
                new AdminReportResolutionRequest(ReportStatus.REJECTED, false, null, "재판정 시도", null)));

        assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
    }

    @ParameterizedTest
    @EnumSource(value = ReportStatus.class, names = {"RESOLVED", "REJECTED"})
    @DisplayName("검토를 시작하지 않은 접수(RECEIVED) 신고는 판정(resolve)할 수 없다 (409)")
    void resolveReport_receivedReport_conflict(ReportStatus decision) {
        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(report));

        assertConflict(() -> reportService.resolveReport(adminId, reportId.toString(),
                new AdminReportResolutionRequest(decision, false, null, "검토 없이 판정 시도", null)));

        assertThat(report.getStatus()).isEqualTo(ReportStatus.RECEIVED);
        verify(reportRepository, never()).save(any(Report.class));
        verify(userPenaltyService, never()).createPenalty(any(), any(), any());
        verify(adminAuditLogRepository, never()).save(any(AdminAuditLog.class));
    }

    // ================= [이슈 #79] MENTORING 신고의 피신고자는 신고자의 상대방 =================

    private UUID mentoringApplicationId;
    private UUID mentoringProgramId;
    private User mentorUser;
    private User menteeUser;
    private MentoringProgram mentoringProgram;
    private MentoringApplication mentoringApplication;

    private void setUpMentoringApplication() {
        mentoringApplicationId = UUID.randomUUID();
        mentoringProgramId = UUID.randomUUID();

        mentorUser = User.createLocal("mentor@gamerin.com", "mentor01", "멘토", "hash");
        ReflectionTestUtils.setField(mentorUser, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(mentorUser, "createdAt", OffsetDateTime.now());

        menteeUser = User.createLocal("mentee@gamerin.com", "mentee01", "멘티", "hash");
        ReflectionTestUtils.setField(menteeUser, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(menteeUser, "createdAt", OffsetDateTime.now());

        MentorProfile profile = new MentorProfile();
        profile.setUser(mentorUser);
        mentoringProgram = new MentoringProgram();
        ReflectionTestUtils.setField(mentoringProgram, "id", mentoringProgramId);
        mentoringProgram.setMentor(profile);
        mentoringProgram.setTitle("coaching");

        mentoringApplication = new MentoringApplication();
        ReflectionTestUtils.setField(mentoringApplication, "id", mentoringApplicationId);
        mentoringApplication.setProgram(mentoringProgram);
        mentoringApplication.setMentee(menteeUser);
        // 신고 가능 상태(ONGOING/FINISHED)를 기본 픽스처로 사용한다.
        mentoringApplication.setStatus(ApplicationStatus.ONGOING);

        org.mockito.Mockito.lenient()
                .when(mentoringApplicationRepository.findById(mentoringApplicationId))
                .thenReturn(Optional.of(mentoringApplication));
        org.mockito.Mockito.lenient()
                .when(mentoringApplicationRepository.findByIdForUpdate(mentoringApplicationId))
                .thenReturn(Optional.of(mentoringApplication));
    }

    private Report mentoringReportBy(User mentoringReporter) {
        Report mentoringReport = Report.create(
                mentoringReporter,
                ReportTargetType.MENTORING,
                mentoringApplicationId,
                "멘토링 신청 (프로그램: coaching)",
                ReportReasonCode.PROFANITY,
                "멘토링 중 욕설");
        ReflectionTestUtils.setField(mentoringReport, "id", reportId);
        ReflectionTestUtils.setField(mentoringReport, "reportCode", "RPT-2001");
        ReflectionTestUtils.setField(mentoringReport, "createdAt", OffsetDateTime.now());
        ReflectionTestUtils.setField(mentoringReport, "updatedAt", OffsetDateTime.now());
        // 판정(resolve)은 검토 중(IN_REVIEW) 신고만 허용되므로 검토 시작 상태로 둔다.
        ReflectionTestUtils.setField(mentoringReport, "status", ReportStatus.IN_REVIEW);
        return mentoringReport;
    }

    private AdminReportDetailResponse resolveMentoringReportWithSuspension(Report mentoringReport) {
        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(mentoringReport));
        given(reportRepository.findById(reportId)).willReturn(Optional.of(mentoringReport));
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(reportRepository.countByTargetTypeAndTargetId(eq(ReportTargetType.USER), any(UUID.class)))
                .willReturn(0L);
        given(userPenaltyService.getUserPenalties(any(UUID.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        return reportService.resolveReport(adminId, reportId.toString(), new AdminReportResolutionRequest(
                ReportStatus.RESOLVED, false, PenaltyType.SUSPENSION_7D, "멘토링 중 욕설 확인", null));
    }

    @Test
    @DisplayName("[#79] 멘토가 신청 건을 신고하면 멘티가 피신고자로 식별되고 제재도 멘티에게만 부과된다")
    void resolveMentoringReport_byMentor_sanctionsMentee() {
        setUpMentoringApplication();

        AdminReportDetailResponse response = resolveMentoringReportWithSuspension(mentoringReportBy(mentorUser));

        verify(userPenaltyService).createPenalty(eq(adminId), eq(menteeUser.getId()),
                any(UserPenaltyCreateRequest.class));
        verify(userPenaltyService, never()).createPenalty(any(), eq(mentorUser.getId()), any());
        assertThat(response.targetUser().id()).isEqualTo(menteeUser.getId());
    }

    @Test
    @DisplayName("[#79] 멘티가 신청 건을 신고하면 멘토가 피신고자로 식별되고 제재도 멘토에게만 부과된다")
    void resolveMentoringReport_byMentee_sanctionsMentor() {
        setUpMentoringApplication();
        mentoringApplication.setStatus(ApplicationStatus.ONGOING);
        mentoringApplication.setPaymentStatus(PaymentStatus.ESCROW_HELD);

        AdminReportDetailResponse response = resolveMentoringReportWithSuspension(mentoringReportBy(menteeUser));

        verify(userPenaltyService).createPenalty(eq(adminId), eq(mentorUser.getId()),
                any(UserPenaltyCreateRequest.class));
        verify(userPenaltyService, never()).createPenalty(any(), eq(menteeUser.getId()), any());
        assertThat(response.targetUser().id()).isEqualTo(mentorUser.getId());
    }

    @Test
    @DisplayName("[#79] 신청 건 참여자가 아닌 유저의 MENTORING 신고는 404로 거부되고 저장되지 않는다")
    void createMentoringReport_byNonParticipant_rejected() {
        setUpMentoringApplication();
        given(userRepository.findById(reporterId)).willReturn(Optional.of(reporter));
        CustomUserPrincipal principal = CustomUserPrincipal.from(reporter);

        assertThatThrownBy(() -> reportService.createReport(principal, new ReportCreateRequest(
                ReportTargetType.MENTORING, mentoringApplicationId, ReportReasonCode.PROFANITY, "참여하지 않은 신청 건")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[#79] 신청 건 참여자(멘토)의 MENTORING 신고는 정상 접수된다")
    void createMentoringReport_byParticipant_accepted() {
        setUpMentoringApplication();
        given(userRepository.findById(mentorUser.getId())).willReturn(Optional.of(mentorUser));
        given(reportRepository.saveAndFlush(any(Report.class))).willAnswer(invocation -> {
            Report saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", reportId);
            given(reportRepository.findById(reportId)).willReturn(Optional.of(saved));
            return saved;
        });
        given(reportCountRepository.findByTargetTypeAndTargetId(ReportTargetType.MENTORING, mentoringApplicationId))
                .willReturn(Optional.of(ReportCount.create(ReportTargetType.MENTORING, mentoringApplicationId)));

        reportService.createReport(CustomUserPrincipal.from(mentorUser), new ReportCreateRequest(
                ReportTargetType.MENTORING, mentoringApplicationId, ReportReasonCode.PROFANITY, "멘티의 욕설"));

        verify(reportRepository).saveAndFlush(any(Report.class));
    }

    // ================= [락 선행 로드: 신청 건 → 신고 순서 검증] =================

    /** 신청 건 리포지토리에 대해 해당 ID로 가장 먼저 호출된 메서드 이름 */
    private String firstApplicationRepositoryCallFor(UUID applicationId) {
        return org.mockito.Mockito.mockingDetails(mentoringApplicationRepository).getInvocations().stream()
                .filter(invocation -> invocation.getArguments().length > 0
                        && applicationId.equals(invocation.getArguments()[0]))
                .map(invocation -> invocation.getMethod().getName())
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("[락 순서] 신청 건 신고 접수는 신청 건을 락 조회로 먼저 로드한 뒤 신고를 저장한다")
    void createMentoringReport_locksApplicationBeforeInsertingReport() {
        setUpMentoringApplication();
        mentoringApplication.setStatus(ApplicationStatus.FINISHED);
        given(userRepository.findById(menteeUser.getId())).willReturn(Optional.of(menteeUser));
        given(reportRepository.saveAndFlush(any(Report.class))).willAnswer(invocation -> {
            Report saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", reportId);
            given(reportRepository.findById(reportId)).willReturn(Optional.of(saved));
            return saved;
        });
        given(reportCountRepository.findByTargetTypeAndTargetId(ReportTargetType.MENTORING, mentoringApplicationId))
                .willReturn(Optional.of(ReportCount.create(ReportTargetType.MENTORING, mentoringApplicationId)));

        reportService.createReport(CustomUserPrincipal.from(menteeUser), new ReportCreateRequest(
                ReportTargetType.MENTORING, mentoringApplicationId, ReportReasonCode.OTHER, "멘토 잠수"));

        assertThat(firstApplicationRepositoryCallFor(mentoringApplicationId)).isEqualTo("findByIdForUpdate");
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(mentoringApplicationRepository, reportRepository);
        inOrder.verify(mentoringApplicationRepository).findByIdForUpdate(mentoringApplicationId);
        inOrder.verify(reportRepository).saveAndFlush(any(Report.class));
    }

    @Test
    @DisplayName("[락 순서] 신고 판정 시 신청 건은 락 조회로 처음 로드되어 락 없는 findById가 앞서지 않는다")
    void resolveMentoringReport_loadsApplicationWithLockFirst() {
        setUpMentoringApplication();
        mentoringApplication.setStatus(ApplicationStatus.FINISHED);
        mentoringApplication.setPaymentStatus(PaymentStatus.ESCROW_HELD);

        Report menteeReport = mentoringReportBy(menteeUser);
        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(menteeReport));
        given(reportRepository.findById(reportId)).willReturn(Optional.of(menteeReport));
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(reportRepository.countByTargetTypeAndTargetId(eq(ReportTargetType.USER), any(UUID.class)))
                .willReturn(0L);
        given(userPenaltyService.getUserPenalties(any(UUID.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        reportService.resolveReport(adminId, reportId.toString(), new AdminReportResolutionRequest(
                ReportStatus.RESOLVED, false, null, "멘토 미진행 확인으로 환불 처리", null));

        assertThat(firstApplicationRepositoryCallFor(mentoringApplicationId)).isEqualTo("findByIdForUpdate");
        verify(adminMentoringService).forceRefund(adminId, mentoringApplicationId, "멘토 미진행 확인으로 환불 처리");
    }

    // ================= [멘토링 이의제기(신고) 에스크로 연동 및 프로그램 신고 검증] =================

    // ================= [환불/정산 수행 결과 명시 (silent no-op 방지)] =================

    private AdminReportDetailResponse resolveMentoringReport(Report mentoringReport, ReportStatus decision) {
        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(mentoringReport));
        org.mockito.Mockito.lenient().when(reportRepository.findById(reportId)).thenReturn(Optional.of(mentoringReport));
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        org.mockito.Mockito.lenient().when(reportRepository.countByTargetTypeAndTargetId(eq(ReportTargetType.USER), any(UUID.class)))
                .thenReturn(0L);
        org.mockito.Mockito.lenient().when(userPenaltyService.getUserPenalties(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        return reportService.resolveReport(adminId, reportId.toString(), new AdminReportResolutionRequest(
                decision, false, null, "처리 사유", null));
    }

    @Test
    @DisplayName("[#3] 멘티 신고 RESOLVED로 환불되면 응답과 감사 로그에 REFUNDED가 남는다")
    void resolveMentoringReport_refund_reportsRefundedOutcome() {
        setUpMentoringApplication();
        mentoringApplication.setStatus(ApplicationStatus.ONGOING);
        mentoringApplication.setPaymentStatus(PaymentStatus.ESCROW_HELD);

        AdminReportDetailResponse response = resolveMentoringReport(mentoringReportBy(menteeUser),
                ReportStatus.RESOLVED);

        assertThat(response.escrowResult().outcome()).isEqualTo(AdminReportDetailResponse.EscrowOutcome.REFUNDED);
        verify(adminMentoringService).forceRefund(adminId, mentoringApplicationId, "처리 사유");
        org.mockito.ArgumentCaptor<AdminAuditLog> captor = org.mockito.ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(adminAuditLogRepository, atLeast(1)).save(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(log -> assertThat(log.getDetails()).contains("REFUNDED"));
    }

    @Test
    @DisplayName("[#3] 멘티 신고 RESOLVED인데 에스크로 보관 상태가 아니면 409로 거절되고 환불·정산이 일어나지 않는다")
    void resolveMentoringReport_resolvedButNotEscrowHeld_conflict() {
        setUpMentoringApplication();
        mentoringApplication.setStatus(ApplicationStatus.COMPLETED);
        mentoringApplication.setPaymentStatus(PaymentStatus.SETTLED);

        assertThatThrownBy(() -> resolveMentoringReport(mentoringReportBy(menteeUser), ReportStatus.RESOLVED))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(adminMentoringService, never()).forceRefund(any(), any(), any());
        verify(adminMentoringService, never()).forceSettle(any(), any(), any());
    }

    @Test
    @DisplayName("[#3] 멘토가 낸 신고는 환불/정산 대상이 아님을 NOT_PERFORMED로 명시하고 신고만 처리한다")
    void resolveMentoringReport_byMentor_reportsNotPerformed() {
        setUpMentoringApplication();
        mentoringApplication.setStatus(ApplicationStatus.FINISHED);
        mentoringApplication.setPaymentStatus(PaymentStatus.ESCROW_HELD);

        AdminReportDetailResponse response = resolveMentoringReport(mentoringReportBy(mentorUser),
                ReportStatus.RESOLVED);

        assertThat(response.escrowResult().outcome())
                .isEqualTo(AdminReportDetailResponse.EscrowOutcome.NOT_PERFORMED);
        verify(adminMentoringService, never()).forceRefund(any(), any(), any());
        verify(adminMentoringService, never()).forceSettle(any(), any(), any());
    }

    @Test
    @DisplayName("[#3] 진행 중(ONGOING) 건 멘티 신고를 REJECTED하면 정산 없이 NOT_PERFORMED로 명시된다")
    void resolveMentoringReport_rejectedOngoing_reportsNotPerformed() {
        setUpMentoringApplication();
        mentoringApplication.setStatus(ApplicationStatus.ONGOING);
        mentoringApplication.setPaymentStatus(PaymentStatus.ESCROW_HELD);

        AdminReportDetailResponse response = resolveMentoringReport(mentoringReportBy(menteeUser),
                ReportStatus.REJECTED);

        assertThat(response.escrowResult().outcome())
                .isEqualTo(AdminReportDetailResponse.EscrowOutcome.NOT_PERFORMED);
        verify(adminMentoringService, never()).forceSettle(any(), any(), any());
    }

    // ================= [신고 단계(stage)별 재신고 허용] =================

    private void stubReportSaveForMentoring() {
        given(reportRepository.saveAndFlush(any(Report.class))).willAnswer(invocation -> {
            Report saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", reportId);
            given(reportRepository.findById(reportId)).willReturn(Optional.of(saved));
            return saved;
        });
        given(reportCountRepository.findByTargetTypeAndTargetId(ReportTargetType.MENTORING, mentoringApplicationId))
                .willReturn(Optional.of(ReportCount.create(ReportTargetType.MENTORING, mentoringApplicationId)));
    }

    @Test
    @DisplayName("[#4] ONGOING 단계 신고가 이미 있으면 같은 단계 재신고는 409로 거절된다")
    void createMentoringReport_sameStage_conflict() {
        setUpMentoringApplication();
        mentoringApplication.setStatus(ApplicationStatus.ONGOING);
        given(userRepository.findById(menteeUser.getId())).willReturn(Optional.of(menteeUser));
        given(reportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStage(
                menteeUser.getId(), ReportTargetType.MENTORING, mentoringApplicationId, ReportStage.ONGOING))
                .willReturn(true);

        assertThatThrownBy(() -> reportService.createReport(CustomUserPrincipal.from(menteeUser),
                new ReportCreateRequest(ReportTargetType.MENTORING, mentoringApplicationId,
                        ReportReasonCode.OTHER, "중복 신고")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[#4] ONGOING 단계 신고가 반려·종결된 뒤에도 FINISHED 단계에서는 다시 신고할 수 있다")
    void createMentoringReport_afterOngoingReport_allowedInFinishedStage() {
        setUpMentoringApplication();
        mentoringApplication.setStatus(ApplicationStatus.FINISHED);
        given(userRepository.findById(menteeUser.getId())).willReturn(Optional.of(menteeUser));
        given(reportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStage(
                menteeUser.getId(), ReportTargetType.MENTORING, mentoringApplicationId, ReportStage.FINISHED))
                .willReturn(false);
        stubReportSaveForMentoring();

        reportService.createReport(CustomUserPrincipal.from(menteeUser),
                new ReportCreateRequest(ReportTargetType.MENTORING, mentoringApplicationId,
                        ReportReasonCode.OTHER, "멘토 일방 종료"));

        org.mockito.ArgumentCaptor<Report> captor = org.mockito.ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStage()).isEqualTo(ReportStage.FINISHED);
        verify(reportRepository, never()).existsByReporterIdAndTargetTypeAndTargetIdAndStage(
                any(), any(), any(), eq(ReportStage.ONGOING));
    }

    @Test
    @DisplayName("[#4] 진행 단계(APPLIED/ACCEPTED/ONGOING) 신고는 ONGOING 단계로 접수된다")
    void createMentoringReport_beforeFinished_isOngoingStage() {
        setUpMentoringApplication();
        mentoringApplication.setStatus(ApplicationStatus.ONGOING);
        given(userRepository.findById(menteeUser.getId())).willReturn(Optional.of(menteeUser));
        stubReportSaveForMentoring();

        reportService.createReport(CustomUserPrincipal.from(menteeUser),
                new ReportCreateRequest(ReportTargetType.MENTORING, mentoringApplicationId,
                        ReportReasonCode.OTHER, "멘토 잠수"));

        org.mockito.ArgumentCaptor<Report> captor = org.mockito.ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStage()).isEqualTo(ReportStage.ONGOING);
    }

    @Test
    @DisplayName("[#9] 프로그램 직접 신고 상세는 프로그램이 숨김(소프트 삭제)되면 contentHidden이 true다")
    void getAdminReportDetail_programDirectReport_contentHiddenReflectsProgramDeletion() {
        setUpMentoringApplication();
        mentoringProgram.softDelete();

        Report programReport = Report.create(reporter, ReportTargetType.MENTORING, mentoringProgramId,
                "프로그램 스냅샷", ReportReasonCode.PROFANITY, "부적절한 프로그램");
        ReflectionTestUtils.setField(programReport, "id", reportId);
        ReflectionTestUtils.setField(programReport, "reportCode", "RPT-3001");
        ReflectionTestUtils.setField(programReport, "createdAt", OffsetDateTime.now());
        ReflectionTestUtils.setField(programReport, "updatedAt", OffsetDateTime.now());

        given(reportRepository.findById(reportId)).willReturn(Optional.of(programReport));
        given(mentoringApplicationRepository.findById(mentoringProgramId)).willReturn(Optional.empty());
        given(mentoringProgramRepository.findById(mentoringProgramId)).willReturn(Optional.of(mentoringProgram));
        org.mockito.Mockito.lenient()
                .when(reportRepository.countByTargetTypeAndTargetId(eq(ReportTargetType.USER), any(UUID.class)))
                .thenReturn(0L);
        org.mockito.Mockito.lenient()
                .when(userPenaltyService.getUserPenalties(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        AdminReportDetailResponse response = reportService.getAdminReportDetail(reportId.toString());

        assertThat(response.contentHidden()).isTrue();
    }

    @Test
    @DisplayName("[#9] 신청 건 신고 상세의 contentHidden은 항상 false다")
    void getAdminReportDetail_applicationReport_contentHiddenAlwaysFalse() {
        setUpMentoringApplication();
        mentoringProgram.softDelete();

        given(reportRepository.findById(reportId)).willReturn(Optional.of(mentoringReportBy(menteeUser)));
        org.mockito.Mockito.lenient()
                .when(reportRepository.countByTargetTypeAndTargetId(eq(ReportTargetType.USER), any(UUID.class)))
                .thenReturn(0L);
        org.mockito.Mockito.lenient()
                .when(userPenaltyService.getUserPenalties(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        AdminReportDetailResponse response = reportService.getAdminReportDetail(reportId.toString());

        assertThat(response.contentHidden()).isFalse();
    }
    @Test
    @DisplayName("멘티가 에스크로 보관(ESCROW_HELD) 신청 건을 이의제기(신고)하고 어드민이 RESOLVED 판정 시 멘티에게 강제 환불된다")
    void resolveMentoringReport_byMentee_resolved_triggersForceRefund() {
        setUpMentoringApplication();
        mentoringApplication.setStatus(ApplicationStatus.FINISHED);
        mentoringApplication.setPaymentStatus(PaymentStatus.ESCROW_HELD);

        Report menteeReport = mentoringReportBy(menteeUser);
        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(menteeReport));
        given(reportRepository.findById(reportId)).willReturn(Optional.of(menteeReport));
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(reportRepository.countByTargetTypeAndTargetId(eq(ReportTargetType.USER), any(UUID.class)))
                .willReturn(0L);
        given(userPenaltyService.getUserPenalties(any(UUID.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        reportService.resolveReport(adminId, reportId.toString(), new AdminReportResolutionRequest(
                ReportStatus.RESOLVED, false, null, "멘토 미진행 확인으로 환불 처리", null));

        verify(adminMentoringService).forceRefund(adminId, mentoringApplicationId, "멘토 미진행 확인으로 환불 처리");
        verify(adminMentoringService, never()).forceSettle(any(), any(), any());
    }

    @Test
    @DisplayName("종료 보고(FINISHED)된 신청 건의 멘티 이의제기를 어드민이 REJECTED(반려) 판정 시 멘토에게 강제 정산된다")
    void resolveMentoringReport_byMentee_rejectedWhenFinished_triggersForceSettle() {
        setUpMentoringApplication();
        mentoringApplication.setStatus(ApplicationStatus.FINISHED);
        mentoringApplication.setPaymentStatus(PaymentStatus.ESCROW_HELD);

        Report menteeReport = mentoringReportBy(menteeUser);
        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(menteeReport));
        given(reportRepository.findById(reportId)).willReturn(Optional.of(menteeReport));
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(reportRepository.countByTargetTypeAndTargetId(eq(ReportTargetType.USER), any(UUID.class)))
                .willReturn(0L);
        given(userPenaltyService.getUserPenalties(any(UUID.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        reportService.resolveReport(adminId, reportId.toString(), new AdminReportResolutionRequest(
                ReportStatus.REJECTED, false, null, "정상 진행 증빙 확인됨", null));

        verify(adminMentoringService).forceSettle(adminId, mentoringApplicationId, "정상 진행 증빙 확인됨");
        verify(adminMentoringService, never()).forceRefund(any(), any(), any());
    }

    @Test
    @DisplayName("진행 중(ONGOING)인 신청 건의 멘티 신고를 REJECTED(반려) 판정 시 정산하지 않고 진행 상태를 유지한다")
    void resolveMentoringReport_byMentee_rejectedWhenOngoing_doesNotSettle() {
        setUpMentoringApplication();
        mentoringApplication.setStatus(ApplicationStatus.ONGOING);
        mentoringApplication.setPaymentStatus(PaymentStatus.ESCROW_HELD);

        Report menteeReport = mentoringReportBy(menteeUser);
        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(menteeReport));
        given(reportRepository.findById(reportId)).willReturn(Optional.of(menteeReport));
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(reportRepository.countByTargetTypeAndTargetId(eq(ReportTargetType.USER), any(UUID.class)))
                .willReturn(0L);
        given(userPenaltyService.getUserPenalties(any(UUID.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        reportService.resolveReport(adminId, reportId.toString(), new AdminReportResolutionRequest(
                ReportStatus.REJECTED, false, null, "단순 오해로 확인되어 반려", null));

        verify(adminMentoringService, never()).forceSettle(any(), any(), any());
        verify(adminMentoringService, never()).forceRefund(any(), any(), any());
    }

    @Test
    @DisplayName("[#5] 신청(APPLIED)·수락(ACCEPTED) 상태 신청 건은 신고할 수 없고 사유와 함께 409로 거절된다")
    void createMentoringReport_beforeOngoing_conflict() {
        for (ApplicationStatus status : List.of(ApplicationStatus.APPLIED, ApplicationStatus.ACCEPTED)) {
            setUpMentoringApplication();
            mentoringApplication.setStatus(status);
            given(userRepository.findById(menteeUser.getId())).willReturn(Optional.of(menteeUser));

            assertThatThrownBy(() -> reportService.createReport(CustomUserPrincipal.from(menteeUser),
                    new ReportCreateRequest(ReportTargetType.MENTORING, mentoringApplicationId,
                            ReportReasonCode.OTHER, "아직 시작 전")))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getReason()).contains(status.name());
                    });
        }
        verify(reportRepository, never()).saveAndFlush(any());
    }
    @Test
    @DisplayName("이미 정산 완료(COMPLETED)된 신청 건에 대해서는 신고(이의제기)를 접수할 수 없다")
    void createMentoringReport_whenAlreadyCompleted_rejected() {
        setUpMentoringApplication();
        mentoringApplication.setStatus(ApplicationStatus.COMPLETED);
        given(userRepository.findById(menteeUser.getId())).willReturn(Optional.of(menteeUser));

        assertThatThrownBy(() -> reportService.createReport(
                CustomUserPrincipal.from(menteeUser),
                new ReportCreateRequest(
                        ReportTargetType.MENTORING,
                        mentoringApplicationId,
                        ReportReasonCode.OTHER,
                        "이미 완료된 멘토링 뒤늦은 신고")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("일반 유저는 타인의 멘토링 프로그램(MentoringProgram) 자체를 신고할 수 있으며, 본인 프로그램은 신고할 수 없다")
    void createMentoringProgramReport_allowedForOtherUser_rejectedForOwner() {
        setUpMentoringApplication();
        given(mentoringApplicationRepository.findById(mentoringProgramId)).willReturn(Optional.empty());
        given(mentoringProgramRepository.findByIdAndDeletedAtIsNull(mentoringProgramId))
                .willReturn(Optional.of(mentoringProgram));
        given(mentoringProgramRepository.findById(mentoringProgramId))
                .willReturn(Optional.of(mentoringProgram));

        // 1. 멘토 본인이 자기 프로그램을 신고하면 404 거부
        given(userRepository.findById(mentorUser.getId())).willReturn(Optional.of(mentorUser));
        assertThatThrownBy(() -> reportService.createReport(
                CustomUserPrincipal.from(mentorUser),
                new ReportCreateRequest(
                        ReportTargetType.MENTORING,
                        mentoringProgramId,
                        ReportReasonCode.SPAM,
                        "본인 프로그램 신고 시도")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        // 2. 타인(일반 유저)이 해당 프로그램을 신고하면 정상 접수
        given(userRepository.findById(reporterId)).willReturn(Optional.of(reporter));
        given(reportRepository.saveAndFlush(any(Report.class))).willAnswer(invocation -> {
            Report saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", reportId);
            given(reportRepository.findById(reportId)).willReturn(Optional.of(saved));
            return saved;
        });
        given(reportCountRepository.findByTargetTypeAndTargetId(ReportTargetType.MENTORING, mentoringProgramId))
                .willReturn(Optional.of(ReportCount.create(ReportTargetType.MENTORING, mentoringProgramId)));

        reportService.createReport(
                CustomUserPrincipal.from(reporter),
                new ReportCreateRequest(
                        ReportTargetType.MENTORING,
                        mentoringProgramId,
                        ReportReasonCode.PROFANITY,
                        "프로그램 소개글 내 비방 포함"));

        verify(reportRepository).saveAndFlush(any(Report.class));
    }

    @Test
    @DisplayName("멘토링 프로그램 신고를 RESOLVED 및 콘텐츠 숨김 체크로 판정하면 프로그램 강제 숨김(hideProgram)이 호출된다")
    void resolveMentoringProgramReport_withHideContent_triggersHideProgram() {
        setUpMentoringApplication();
        given(mentoringApplicationRepository.findByIdForUpdate(mentoringProgramId)).willReturn(Optional.empty());
        given(mentoringProgramRepository.findByIdAndDeletedAtIsNull(mentoringProgramId))
                .willReturn(Optional.of(mentoringProgram));
        given(mentoringProgramRepository.findById(mentoringProgramId))
                .willReturn(Optional.of(mentoringProgram));

        Report programReport = Report.create(
                reporter,
                ReportTargetType.MENTORING,
                mentoringProgramId,
                "멘토링 프로그램: coaching",
                ReportReasonCode.PROFANITY,
                "비방 프로그램");
        ReflectionTestUtils.setField(programReport, "id", reportId);
        ReflectionTestUtils.setField(programReport, "reportCode", "RPT-3001");
        ReflectionTestUtils.setField(programReport, "createdAt", OffsetDateTime.now());
        ReflectionTestUtils.setField(programReport, "updatedAt", OffsetDateTime.now());
        ReflectionTestUtils.setField(programReport, "status", ReportStatus.IN_REVIEW);

        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(programReport));
        given(reportRepository.findById(reportId)).willReturn(Optional.of(programReport));
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(reportRepository.countByTargetTypeAndTargetId(eq(ReportTargetType.USER), any(UUID.class)))
                .willReturn(0L);
        given(userPenaltyService.getUserPenalties(any(UUID.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        reportService.resolveReport(adminId, reportId.toString(), new AdminReportResolutionRequest(
                ReportStatus.RESOLVED, true, null, "부적절한 멘토링 프로그램 숨김 처리", null));

        verify(adminMentoringService).hideProgram(adminId, mentoringProgramId, "부적절한 멘토링 프로그램 숨김 처리");
    }

    // ===== [#134] 본인 대상 신고 차단 =====

    private void assertSelfReportRejected(ReportTargetType type, UUID targetId) {
        given(userRepository.findById(reporterId)).willReturn(Optional.of(reporter));
        CustomUserPrincipal principal = CustomUserPrincipal.from(reporter);

        assertThatThrownBy(() -> reportService.createReport(principal,
                new ReportCreateRequest(type, targetId, ReportReasonCode.PROFANITY, "본인 대상 신고")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(reportRepository, never()).saveAndFlush(any());
        verify(reportCountRepository, never()).findByTargetTypeAndTargetId(any(), any());
    }

    @Test
    @DisplayName("[#134] 본인 게시글 신고는 400으로 거부된다")
    void createReport_ownPost_rejected() {
        Post own = Post.create(reporter, "내 글");
        given(postRepository.findByIdAndDeletedAtIsNull(postId)).willReturn(Optional.of(own));

        assertSelfReportRejected(ReportTargetType.POST, postId);
    }

    @Test
    @DisplayName("[#134] 본인 댓글 신고는 400으로 거부된다")
    void createReport_ownComment_rejected() {
        UUID commentId = UUID.randomUUID();
        PostComment comment = PostComment.create(post, reporter, "내 댓글");
        given(postCommentRepository.findById(commentId)).willReturn(Optional.of(comment));

        assertSelfReportRejected(ReportTargetType.COMMENT, commentId);
    }

    @Test
    @DisplayName("[#134] 본인 계정(USER) 신고는 400으로 거부된다")
    void createReport_self_rejected() {
        given(userRepository.findByIdAndDeletedAtIsNull(reporterId)).willReturn(Optional.of(reporter));

        assertSelfReportRejected(ReportTargetType.USER, reporterId);
    }

    @Test
    @DisplayName("[#134] 본인이 보낸 메시지 신고는 400으로 거부된다")
    void createReport_ownMessage_rejected() {
        UUID messageId = UUID.randomUUID();
        DirectMessage own = DirectMessage.create(null, reporter, "내가 보낸 메시지", null);
        given(directMessageRepository.findActiveByIdAndParticipantUserId(messageId, reporterId))
                .willReturn(Optional.of(own));

        assertSelfReportRejected(ReportTargetType.MESSAGE, messageId);
    }

    @Test
    @DisplayName("[#134] 판정 시 피신고자가 신고자 본인이면(기존 접수분) 제재를 부과하지 않는다")
    void resolveReport_targetIsReporter_skipsPenalty() {
        UUID messageId = UUID.randomUUID();
        Report selfReport = Report.create(reporter, ReportTargetType.MESSAGE, messageId, "내 메시지",
                ReportReasonCode.PROFANITY, "본인 메시지 신고");
        ReflectionTestUtils.setField(selfReport, "id", reportId);
        ReflectionTestUtils.setField(selfReport, "reportCode", "RPT-1002");
        ReflectionTestUtils.setField(selfReport, "createdAt", OffsetDateTime.now());
        ReflectionTestUtils.setField(selfReport, "updatedAt", OffsetDateTime.now());
        ReflectionTestUtils.setField(selfReport, "status", ReportStatus.IN_REVIEW);

        given(reportRepository.findByIdForUpdate(reportId)).willReturn(Optional.of(selfReport));
        given(reportRepository.findById(reportId)).willReturn(Optional.of(selfReport));
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(directMessageRepository.findById(messageId))
                .willReturn(Optional.of(DirectMessage.create(null, reporter, "내 메시지", null)));
        given(reportRepository.countByTargetTypeAndTargetId(eq(ReportTargetType.USER), any(UUID.class)))
                .willReturn(0L);
        given(userPenaltyService.getUserPenalties(any(UUID.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        reportService.resolveReport(adminId, reportId.toString(), new AdminReportResolutionRequest(
                ReportStatus.RESOLVED, false, PenaltyType.SUSPENSION_7D, "제재 시도", null));

        verify(userPenaltyService, never()).createPenalty(any(), any(), any());
        assertThat(selfReport.getStatus()).isEqualTo(ReportStatus.RESOLVED);
    }
}
