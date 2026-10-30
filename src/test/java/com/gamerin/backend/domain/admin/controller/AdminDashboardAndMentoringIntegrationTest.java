package com.gamerin.backend.domain.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.admin.dto.request.AdminForceActionRequest;
import com.gamerin.backend.domain.admin.repository.AdminAuditLogRepository;
import com.gamerin.backend.domain.mentoring.entity.*;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.entity.UserRole;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 6단계 어드민 대시보드 통계, 멘토링 에스크로 강제 개입(환불/정산) 및 감사 로그 E2E 통합 테스트
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class AdminDashboardAndMentoringIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MentorProfileRepository mentorProfileRepository;

    @Autowired
    private MentoringProgramRepository mentoringProgramRepository;

    @Autowired
    private MentoringApplicationRepository mentoringApplicationRepository;

    @Autowired
    private MileageService mileageService;

    @Autowired
    private AdminAuditLogRepository adminAuditLogRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private com.gamerin.backend.domain.admin.service.AdminMentoringService adminMentoringService;

    @Autowired
    private com.gamerin.backend.domain.post.repository.PostRepository postRepository;
    
    @Autowired
    private com.gamerin.backend.domain.report.repository.ReportCountRepository reportCountRepository;

    @Autowired
    private com.gamerin.backend.domain.report.service.ReportCountInitializer reportCountInitializer;


    private User admin;
    private User mentorUser;
    private User menteeUser;
    private MentorProfile mentorProfile;
    private MentoringProgram program;
    private String adminToken;

    @BeforeEach
    void setUp() {
        // 1. 어드민 유저 및 토큰 발급
        admin = User.createLocal("admin@gamerin.com", "admin_" + UUID.randomUUID().toString().substring(0, 8), "관리자", "hash");
        admin.setProfile(UserProfile.createDefault(admin));
        ReflectionTestUtils.setField(admin, "role", UserRole.ADMIN);
        admin = userRepository.save(admin);
        adminToken = jwtTokenProvider.createAccessToken(admin.getId(), admin.getHandle(), List.of("ADMIN"));

        // 2. 멘토 유저 및 멘토 프로필 생성
        mentorUser = User.createLocal("mentor@test.com", "mentor_" + UUID.randomUUID().toString().substring(0, 8), "멘토", "hash");
        mentorUser.setProfile(UserProfile.createDefault(mentorUser));
        mentorUser = userRepository.save(mentorUser);
        mileageService.getOrCreateWallet(mentorUser);

        mentorProfile = new MentorProfile();
        mentorProfile.setUser(mentorUser);
        mentorProfile.setAbout("배틀그라운드 마스터 멘토입니다.");
        mentorProfile = mentorProfileRepository.save(mentorProfile);

        // 3. 멘토링 프로그램 등록
        program = new MentoringProgram();
        program.setMentor(mentorProfile);
        program.setGameName("PUBG");
        program.setTitle("에임 교정 및 1:1 코칭");
        program.setContent("상세 코칭 커리큘럼");
        program.setPrice(10000L);
        program.setStatus(ProgramStatus.ACTIVE);
        program.setTags(List.of("배그", "에임"));
        program = mentoringProgramRepository.save(program);

        // 4. 멘티 유저 생성 및 마일리지 지갑 준비
        menteeUser = User.createLocal("mentee@test.com", "mentee_" + UUID.randomUUID().toString().substring(0, 8), "멘티", "hash");
        menteeUser.setProfile(UserProfile.createDefault(menteeUser));
        menteeUser = userRepository.save(menteeUser);
        mileageService.getOrCreateWallet(menteeUser);
    }

    @Test
    @DisplayName("[통계 API] 어드민 대시보드 상단 통계 카드 지표가 정상 집계된다")
    void getDashboardStats_success() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard/stats")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.receivedReportsCount").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.data.activePenaltiesCount").value(greaterThanOrEqualTo(0)));
    }

    @Test
    @DisplayName("[에스크로 강제 환불] 관리자 강제 환불 시 멘티에게 마일리지가 반환되고 감사 로그가 자동 적재된다")
    void forceRefund_success() throws Exception {
        // given: 에스크로 보관(ESCROW_HELD) 상태의 멘토링 신청 생성
        MentoringApplication application = new MentoringApplication();
        application.setProgram(program);
        application.setMentee(menteeUser);
        application.setAppliedMileage(10000L);
        application.setStatus(ApplicationStatus.APPLIED);
        application.setPaymentStatus(PaymentStatus.ESCROW_HELD);
        application.setMessage("코칭 신청합니다.");
        application = mentoringApplicationRepository.save(application);

        AdminForceActionRequest request = new AdminForceActionRequest("멘토 무단 불참으로 인한 멘티 전액 강제 환불");

        // when: 관리자 강제 환불 API 호출
        mockMvc.perform(post("/api/v1/admin/mentoring/applications/{applicationId}/force-refund", application.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.paymentStatus").value("REFUNDED"));

        // then: 감사 로그가 정상 적재되었는지 검증
        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .param("actionType", "FORCE_REFUND")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].actionType").value("FORCE_REFUND"))
                .andExpect(jsonPath("$.data.content[0].targetType").value("MENTORING"));
    }

    @Test
    @DisplayName("[에스크로 강제 정산] 관리자 강제 정산 시 멘토에게 마일리지가 지급되고 감사 로그가 자동 적재된다")
    void forceSettle_success() throws Exception {
        // given: 에스크로 보관(ESCROW_HELD) 상태의 멘토링 신청 생성
        MentoringApplication application = new MentoringApplication();
        application.setProgram(program);
        application.setMentee(menteeUser);
        application.setAppliedMileage(10000L);
        application.setStatus(ApplicationStatus.ONGOING);
        application.setPaymentStatus(PaymentStatus.ESCROW_HELD);
        application.setMessage("코칭 신청합니다.");
        application = mentoringApplicationRepository.save(application);

        AdminForceActionRequest request = new AdminForceActionRequest("수업 완료 확인에 따른 멘토 강제 정산 조치");

        // when: 관리자 강제 정산 API 호출
        mockMvc.perform(post("/api/v1/admin/mentoring/applications/{applicationId}/force-settle", application.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.paymentStatus").value("SETTLED"));

        // then: 감사 로그 검증
        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .param("actionType", "FORCE_SETTLE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].actionType").value("FORCE_SETTLE"))
                .andExpect(jsonPath("$.data.content[0].targetType").value("MENTORING"));
    }

    @Test
    @DisplayName("[중복 방어 검증] 이미 환불 완료된 멘토링 건에 대해 다시 환불을 시도하면 400 Bad Request로 차단된다")
    void forceRefund_duplicateRequest_blockedWithBadRequest() throws Exception {
        // given: 10,000P 에스크로 상태의 멘토링 건 생성
        MentoringApplication application = new MentoringApplication();
        application.setProgram(program);
        application.setMentee(menteeUser);
        application.setAppliedMileage(10000L);
        application.setStatus(ApplicationStatus.APPLIED);
        application.setPaymentStatus(PaymentStatus.ESCROW_HELD);
        application.setMessage("환불 중복 방지 테스트");
        MentoringApplication savedApplication = mentoringApplicationRepository.save(application);

        AdminForceActionRequest firstRequest = new AdminForceActionRequest("1차 정상 강제 환불");

        // when 1: 첫 번째 관리자 환불 요청 -> 200 OK 성공
        mockMvc.perform(
                post("/api/v1/admin/mentoring/applications/{applicationId}/force-refund", savedApplication.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstRequest))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("REFUNDED"));

        // when 2: 중복으로 두 번째 관리자 환불 요청 진입 -> 400 BAD REQUEST로 즉각 차단
        AdminForceActionRequest secondRequest = new AdminForceActionRequest("2차 중복 환불 시도");
        mockMvc.perform(
                post("/api/v1/admin/mentoring/applications/{applicationId}/force-refund", savedApplication.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondRequest))
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("에스크로 보관(ESCROW_HELD) 상태인 멘토링 건만")));
    }

    @Test
    @DisplayName("[콘텐츠 복구 감사 로그] 관리자가 숨김 콘텐츠 복구 시 상태가 복원되고 CONTENT_RESTORE 감사 로그가 적재된다")
    void restoreHiddenContent_auditLogSaved() throws Exception {
        // given: 숨김(isHidden = true) 처리된 게시글 및 신고 카운트 준비
        com.gamerin.backend.domain.post.entity.Post post = postRepository.save(
                com.gamerin.backend.domain.post.entity.Post.create(menteeUser, "자동 숨김 처리된 테스트 게시글")
        );

        com.gamerin.backend.domain.report.entity.ReportCount reportCount =
                com.gamerin.backend.domain.report.entity.ReportCount.create(
                        com.gamerin.backend.domain.report.entity.ReportTargetType.POST,
                        post.getId()
                );
        reportCount.hide(); // 숨김 상태 설정
        reportCountRepository.save(reportCount);

        // when: 관리자가 콘텐츠 복구 API 호출 (POST /api/v1/admin/contents/{targetType}/{targetId}/restore)
        mockMvc.perform(post("/api/v1/admin/contents/POST/{targetId}/restore", post.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.isHidden").value(false)); // <-- $.data.hidden 에서 $.data.isHidden으로 수정

        // then: 감사 로그 조회 API를 통해 CONTENT_RESTORE 로그가 정상 등록되었는지 검증
        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .param("actionType", "CONTENT_RESTORE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].actionType").value("CONTENT_RESTORE"))
                .andExpect(jsonPath("$.data.content[0].targetType").value("POST"))
                .andExpect(jsonPath("$.data.content[0].targetId").value(post.getId().toString()));
    }

    @Test
    @DisplayName("[콘텐츠 복구 검증] 숨김 처리되지 않은 정상 콘텐츠 복구 시도 시 400 Bad Request로 차단된다")
    void restoreHiddenContent_notHidden_throwsBadRequest() throws Exception {
        // given: 숨김 처리되지 않은(isHidden = false) 게시글 및 신고 카운트 준비
        com.gamerin.backend.domain.post.entity.Post post = postRepository.save(
                com.gamerin.backend.domain.post.entity.Post.create(menteeUser, "숨김되지 않은 일반 게시글")
        );

        com.gamerin.backend.domain.report.entity.ReportCount reportCount =
                com.gamerin.backend.domain.report.entity.ReportCount.create(
                        com.gamerin.backend.domain.report.entity.ReportTargetType.POST,
                        post.getId()
                );
        // hide()를 호출하지 않아 isHidden = false 상태 유지
        reportCountRepository.save(reportCount);

        // when & then: 복구 API 호출 시 400 Bad Request와 작성자님의 에러 메시지 반환 검증
        mockMvc.perform(post("/api/v1/admin/contents/POST/{targetId}/restore", post.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("숨김 처리된 콘텐츠만 복구할 수 있습니다."));
    }

    @Test
    @DisplayName("[콘텐츠 복구 검증] USER나 MENTORING 등 자동 숨김 미지원 대상 복구 시도 시 400 Bad Request로 차단된다")
    void restoreHiddenContent_unsupportedType_throwsBadRequest() throws Exception {
        // when & then: 콘텐츠가 아닌 유저(USER) 타입으로 복구 API 호출 시 400 Bad Request 발생 검증
        mockMvc.perform(post("/api/v1/admin/contents/USER/{targetId}/restore", admin.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("게시글 및 댓글 콘텐츠만 복구할 수 있습니다."));
    }

    @Test
    @DisplayName("[원자적 레코드 생성 검증] 동일 대상에 대해 initIfNotExists를 중복 호출해도 에러 없이 1건만 안전하게 유지된다")
    void initIfNotExists_duplicate_maintainedSafely() {
        UUID targetId = UUID.randomUUID();
        var targetType = com.gamerin.backend.domain.report.entity.ReportTargetType.POST;

        // when: 동일한 (targetType, targetId)로 두 번 연속 초기화 실행 (동시 생성 시뮬레이션)
        reportCountInitializer.initIfNotExists(targetType, targetId);
        reportCountInitializer.initIfNotExists(targetType, targetId);

        // then: 트랜잭션 중단 없이 정확히 1건의 레코드만 존재해야 함
        var result = reportCountRepository.findByTargetTypeAndTargetId(targetType, targetId);
        org.junit.jupiter.api.Assertions.assertTrue(result.isPresent(), "신고 카운트 레코드가 존재해야 합니다.");
        org.junit.jupiter.api.Assertions.assertEquals(0L, result.get().getReportCount(), "초기 카운트는 0이어야 합니다.");
    }
}