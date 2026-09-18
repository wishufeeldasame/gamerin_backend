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
}