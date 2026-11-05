package com.gamerin.backend.domain.report.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.admin.dto.request.AdminForceActionRequest;
import com.gamerin.backend.domain.admin.dto.request.AdminProgramStatusUpdateRequest;
import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentorStatus;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.PaymentStatus;
import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.report.dto.request.AdminReportResolutionRequest;
import com.gamerin.backend.domain.report.dto.request.ReportCreateRequest;
import com.gamerin.backend.domain.report.entity.ReportReasonCode;
import com.gamerin.backend.domain.report.entity.ReportStatus;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.user.entity.TransactionType;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.entity.UserRole;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;

/**
 * 실제 PostgreSQL + Flyway V1~V49 마이그레이션 환경에서
 * PR 리뷰 1~10번 변경사항(Swagger 시나리오 A~D)을 HTTP API 레벨로 전수 검증하는 E2E 테스트.
 */
@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "REPORT_DETAIL_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MentoringReportE2EPostgresIntegrationTest {

    private static PostgresSchema postgresSchema;

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
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        postgresSchema = PostgresSchema.create();
        registry.add("spring.datasource.url", postgresSchema::url);
        registry.add("spring.datasource.username", postgresSchema::username);
        registry.add("spring.datasource.password", postgresSchema::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.schema", postgresSchema::schema);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.default_schema", postgresSchema::schema);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", postgresSchema::schema);
        registry.add("spring.flyway.default-schema", postgresSchema::schema);
    }

    @AfterAll
    static void tearDownPostgres() {
        if (postgresSchema != null) {
            postgresSchema.drop();
        }
    }

    @Test
    @DisplayName("Swagger 시나리오 A~D 전수 검증 (PostgreSQL + Flyway V48/V49 + MockMvc HTTP API)")
    void fullSwaggerScenarios_A_to_D() throws Exception {
        // 0. 계정 및 토큰 준비 (어드민, 멘토, 멘티, 제3자 유저)
        User admin = createUser("admin", UserRole.ADMIN);
        User mentorUser = createUser("mentor", UserRole.USER);
        User menteeUser = createUser("mentee", UserRole.USER);
        User otherUser = createUser("other", UserRole.USER);

        String adminToken = jwtTokenProvider.createAccessToken(admin.getId(), admin.getHandle(), List.of("ADMIN"));
        String mentorToken = jwtTokenProvider.createAccessToken(mentorUser.getId(), mentorUser.getHandle(), List.of("USER"));
        String menteeToken = jwtTokenProvider.createAccessToken(menteeUser.getId(), menteeUser.getHandle(), List.of("USER"));
        String otherToken = jwtTokenProvider.createAccessToken(otherUser.getId(), otherUser.getHandle(), List.of("USER"));

        UUID[] ids = transactionTemplate.execute(status -> {
            User uMentor = userRepository.findById(mentorUser.getId()).orElseThrow();
            User uMentee = userRepository.findById(menteeUser.getId()).orElseThrow();
            mileageService.getOrCreateWallet(uMentor);
            mileageService.getOrCreateWallet(uMentee);
            mileageService.addMileage(uMentee, 50000L, TransactionType.CHARGE, "테스트 충전", null);

            MentorProfile mp = new MentorProfile();
            mp.setUser(uMentor);
            mp.setStatus(MentorStatus.ACTIVE);
            mp.setAbout("마스터 멘토");
            mp = mentorProfileRepository.save(mp);

            MentoringProgram p = new MentoringProgram();
            p.setMentor(mp);
            p.setGameName("PUBG");
            p.setTitle("실전 코칭 프로그램");
            p.setContent("커리큘럼");
            p.setPrice(10000L);
            p.setStatus(ProgramStatus.ACTIVE);
            p = mentoringProgramRepository.save(p);

            MentoringApplication a = new MentoringApplication();
            a.setProgram(p);
            a.setMentee(uMentee);
            a.setAppliedMileage(10000L);
            a.setStatus(ApplicationStatus.APPLIED);
            a.setPaymentStatus(PaymentStatus.ESCROW_HELD);
            a.setMessage("신청합니다");
            a = mentoringApplicationRepository.save(a);
            return new UUID[] { p.getId(), a.getId() };
        });
        UUID programId = ids[0];
        UUID appId = ids[1];

        // =========================================================================
        // [시나리오 A] APPLIED / ACCEPTED 상태에서는 신고 불가 -> 409 Conflict + 사유 반환 (#5)
        // =========================================================================
        ReportCreateRequest reportReq = new ReportCreateRequest(
                ReportTargetType.MENTORING, appId, ReportReasonCode.OTHER, "시작 전 신고");

        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + menteeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reportReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("진행 중 또는 종료 보고된 멘토링만 신고할 수 있습니다. 현재 상태: APPLIED")));

        // 멘토가 수락(ACCEPTED)해도 여전히 409
        mockMvc.perform(patch("/api/v1/mentoring/applications/{id}/accept", appId)
                        .header("Authorization", "Bearer " + mentorToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + menteeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reportReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("현재 상태: ACCEPTED")));

        // =========================================================================
        // [시나리오 B] ONGOING 시작 -> 1차 신고(stage=ONGOING) -> 반려(NOT_PERFORMED) -> FINISHED 후 재신고(stage=FINISHED) 허용 (#3, #4)
        // =========================================================================
        mockMvc.perform(patch("/api/v1/mentoring/applications/{id}/start", appId)
                        .header("Authorization", "Bearer " + mentorToken))
                .andExpect(status().isOk());

        MvcResult ongoingReportRes = mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + menteeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReportCreateRequest(ReportTargetType.MENTORING, appId, ReportReasonCode.OTHER, "진행 중 오해 신고"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("ONGOING"))
                .andReturn();

        String ongoingReportId = objectMapper.readTree(ongoingReportRes.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // 같은 ONGOING 단계에서 중복 신고 시도 -> 409 Conflict (DB uk_reports_reporter_target_stage 및 애플리케이션 검증)
        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + menteeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReportCreateRequest(ReportTargetType.MENTORING, appId, ReportReasonCode.OTHER, "같은 단계 중복 신고"))))
                .andExpect(status().isConflict());

        // 어드민이 ONGOING 신고를 REJECTED 처리 -> escrowResult.outcome == NOT_PERFORMED
        AdminReportResolutionRequest rejectReq = new AdminReportResolutionRequest(
                ReportStatus.REJECTED, false, null, "진행 중 단순 오해로 반려", "내부 메모");
        // 판정은 검토 중(IN_REVIEW) 신고만 허용되므로 먼저 검토를 시작한다. (이슈 #135)
        mockMvc.perform(post("/api/v1/admin/reports/{idOrCode}/start-review", ongoingReportId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/reports/{idOrCode}/resolve", ongoingReportId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rejectReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.escrowResult.outcome").value("NOT_PERFORMED"));

        // 멘토가 종료 보고(FINISHED)
        mockMvc.perform(patch("/api/v1/mentoring/applications/{id}/finish", appId)
                        .header("Authorization", "Bearer " + mentorToken))
                .andExpect(status().isOk());

        // 멘티가 FINISHED 단계에서 재신고 -> 409로 막히지 않고 stage=FINISHED로 정상 접수!
        MvcResult finishedReportRes = mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + menteeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReportCreateRequest(ReportTargetType.MENTORING, appId, ReportReasonCode.OTHER, "일방적 종료 보고 이의제기"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("FINISHED"))
                .andReturn();

        String finishedReportId = objectMapper.readTree(finishedReportRes.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // =========================================================================
        // [시나리오 C] FINISHED 신고 RESOLVED 인용 -> 강제 환불(REFUNDED) + 양측 알림(mentoring_refunded) 발송 (#1, #3, #10)
        // =========================================================================
        AdminReportResolutionRequest resolveReq = new AdminReportResolutionRequest(
                ReportStatus.RESOLVED, false, null, "멘토 미진행 확인으로 강제 환불", null);
        // 판정은 검토 중(IN_REVIEW) 신고만 허용되므로 먼저 검토를 시작한다. (이슈 #135)
        mockMvc.perform(post("/api/v1/admin/reports/{idOrCode}/start-review", finishedReportId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/reports/{idOrCode}/resolve", finishedReportId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resolveReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.escrowResult.outcome").value("REFUNDED"));

        // 멘티 알림 목록 조회 -> mentoring_refunded 알림 도착 확인 (V49 체크 제약 통과 검증)
        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + menteeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("mentoring_refunded"))
                .andExpect(jsonPath("$.data.items[0].mentoringApplicationId").value(appId.toString()));

        // 멘토 알림 목록 조회 -> mentoring_refunded 알림 도착 확인
        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + mentorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("mentoring_refunded"));

        // =========================================================================
        // [시나리오 D] 프로그램 직접 신고(stage=GENERAL) + 신고 수 합산(2+1=3) + 프로그램 숨김 시 contentHidden=true (#7, #9)
        // =========================================================================
        MvcResult progReportRes = mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReportCreateRequest(ReportTargetType.MENTORING, programId, ReportReasonCode.SPAM, "허위 프로그램 신고"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("GENERAL"))
                .andReturn();

        String progReportId = objectMapper.readTree(progReportRes.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // 숨김 전 프로그램 신고 상세 조회 -> contentHidden == false
        mockMvc.perform(get("/api/v1/admin/reports/{idOrCode}/detail", progReportId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentHidden").value(false));

        // 어드민 프로그램 운영 상태 변경 응답의 reports 수 == 신청 건 신고 2건 + 프로그램 직접 신고 1건 = 3건 (#7)
        mockMvc.perform(patch("/api/v1/admin/mentoring/programs/{programId}/status", programId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminProgramStatusUpdateRequest(ProgramStatus.CLOSED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reports").value(3));

        // 어드민 프로그램 강제 숨김 실행
        mockMvc.perform(post("/api/v1/admin/mentoring/programs/{programId}/hide", programId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminForceActionRequest("악성 프로그램 숨김"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isHidden").value(true))
                .andExpect(jsonPath("$.data.reports").value(3));

        // 숨김 후 프로그램 신고 상세 조회 -> contentHidden == true (#9)
        mockMvc.perform(get("/api/v1/admin/reports/{idOrCode}/detail", progReportId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentHidden").value(true));

        // 신청 건 신고 상세 조회 -> 프로그램이 숨겨졌더라도 신청 건 신고의 contentHidden은 항상 false (#9)
        mockMvc.perform(get("/api/v1/admin/reports/{idOrCode}/detail", finishedReportId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentHidden").value(false));
    }

    private User createUser(String prefix, UserRole role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = User.createLocal(prefix + "_" + suffix + "@gamerin.com", prefix + "_" + suffix, prefix, "hash");
        user.setProfile(UserProfile.createDefault(user));
        ReflectionTestUtils.setField(user, "role", role);
        return userRepository.save(user);
    }

    private record PostgresSchema(String url, String username, String password, String schema) {
        static PostgresSchema create() {
            String baseUrl = System.getenv("REPORT_DETAIL_POSTGRES_TEST_URL");
            String username = System.getenv().getOrDefault("REPORT_DETAIL_POSTGRES_TEST_USERNAME", "postgres");
            String password = System.getenv().getOrDefault("REPORT_DETAIL_POSTGRES_TEST_PASSWORD", "postgres");
            String schema = "test_e2e_" + UUID.randomUUID().toString().replace("-", "");

            try (Connection conn = DriverManager.getConnection(baseUrl, username, password);
                    Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA " + schema);
            } catch (SQLException e) {
                throw new IllegalStateException("PostgreSQL 테스트 스키마 생성 실패", e);
            }

            return new PostgresSchema(baseUrl, username, password, schema);
        }

        void drop() {
            try (Connection conn = DriverManager.getConnection(url, username, password);
                    Statement stmt = conn.createStatement()) {
                stmt.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            } catch (SQLException ignored) {
            }
        }
    }
}
