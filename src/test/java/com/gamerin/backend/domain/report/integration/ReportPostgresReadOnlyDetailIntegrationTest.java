package com.gamerin.backend.domain.report.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.report.dto.response.AdminReportDetailResponse;
import com.gamerin.backend.domain.report.entity.Report;
import com.gamerin.backend.domain.report.entity.ReportCount;
import com.gamerin.backend.domain.report.entity.ReportReasonCode;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.repository.ReportCountRepository;
import com.gamerin.backend.domain.report.repository.ReportRepository;
import com.gamerin.backend.domain.report.service.ReportService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;

/**
 * [이슈 #76] 관리자 신고 상세 조회가 읽기 전용 트랜잭션에서 행 잠금(FOR UPDATE) 없이
 * PostgreSQL 환경에서 정상적으로 200 응답을 반환하는지 검증하는 통합 테스트.
 */
@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "REPORT_DETAIL_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReportPostgresReadOnlyDetailIntegrationTest {

    private static PostgresSchema postgresSchema;

    @Autowired
    private ReportService reportService;
    @Autowired
    private ReportRepository reportRepository;
    @Autowired
    private ReportCountRepository reportCountRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PostRepository postRepository;

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
    @DisplayName("PostgreSQL 환경에서 읽기 전용 트랜잭션으로 관리자 신고 상세 조회 시 500 오류 없이 정상 조회된다")
    void getAdminReportDetail_inPostgres_success() {
        // 1. 유저 및 게시글 생성
        User reporter = User.createLocal("reporter@test.com", "rpt_" + UUID.randomUUID().toString().substring(0, 8),
                "신고자", "hash");
        reporter.setProfile(UserProfile.createDefault(reporter));
        reporter = userRepository.save(reporter);

        User author = User.createLocal("author@test.com", "auth_" + UUID.randomUUID().toString().substring(0, 8), "작성자",
                "hash");
        author.setProfile(UserProfile.createDefault(author));
        author = userRepository.save(author);

        Post post = postRepository.save(Post.create(author, "테스트 게시글 내용입니다."));

        // 2. ReportCount 레코드 생성
        ReportCount reportCount = ReportCount.create(ReportTargetType.POST, post.getId());
        reportCountRepository.save(reportCount);

        // 3. 신고 엔티티 생성
        Report report = Report.create(
                reporter,
                ReportTargetType.POST,
                post.getId(),
                post.getContent(),
                ReportReasonCode.SPAM,
                "스팸 홍보성 게시글입니다.");
        report = reportRepository.save(report);

        // 4. 읽기 전용 트랜잭션(ReportService.getAdminReportDetail) 호출
        // 수정 전에는 PostgreSQL에서 'cannot execute SELECT FOR NO KEY UPDATE in a read-only
        // transaction' (500) 에러 발생
        AdminReportDetailResponse response = reportService.getAdminReportDetail(report.getId().toString());

        // 5. 검증 (실제 Record DTO 필드명 매핑)
        assertThat(response).isNotNull();
        assertThat(response.report().id()).isEqualTo(report.getId());
        assertThat(response.contentHidden()).isFalse();
        assertThat(response.reporter()).isNotNull();
        assertThat(response.reporter().nickname()).isEqualTo("신고자");
    }

    private record PostgresSchema(String url, String username, String password, String schema) {
        static PostgresSchema create() {
            String baseUrl = System.getenv("REPORT_DETAIL_POSTGRES_TEST_URL");
            String username = System.getenv().getOrDefault("REPORT_DETAIL_POSTGRES_TEST_USERNAME", "postgres");
            String password = System.getenv().getOrDefault("REPORT_DETAIL_POSTGRES_TEST_PASSWORD", "postgres");
            String schema = "test_report_" + UUID.randomUUID().toString().replace("-", "");

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