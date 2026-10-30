package com.gamerin.backend.domain.mentoring.integration;

import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 프로그램 검색 쿼리를 실제 PostgreSQL에서 검증한다.
 * null 검색어 파라미터가 bytea로 추론되어 lower(bytea) 오류가 나던 회귀를 막는다.
 */
@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "MENTORING_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminProgramSearchPostgresIntegrationTest {

    private static PostgresSchema postgresSchema;

    @Autowired
    private MentoringProgramRepository mentoringProgramRepository;
    @Autowired
    private MentorProfileRepository mentorProfileRepository;
    @Autowired
    private UserRepository userRepository;
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
        registry.add("spring.flyway.create-schemas", () -> "false");
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
    }

    @BeforeAll
    void setUpPrograms() {
        MentorProfile alpha = createMentorProfile("AlphaCoach");
        MentorProfile beta = createMentorProfile("betaMentor");
        createProgram(alpha, "PUBG 랭크 코칭", ProgramStatus.ACTIVE);
        createProgram(alpha, "Aim Training", ProgramStatus.CLOSED);
        createProgram(beta, "R6 입문 강의", ProgramStatus.ACTIVE);
    }

    @AfterAll
    static void removeTemporarySchema() {
        if (postgresSchema != null) {
            postgresSchema.close();
        }
    }

    @Test
    @DisplayName("검색어가 null이면 상태 필터만 적용해 전체 프로그램을 조회한다")
    void searchWithNullKeyword() {
        assertThat(titles(search(null, null))).containsExactly("Aim Training", "PUBG 랭크 코칭", "R6 입문 강의");
        assertThat(titles(search(ProgramStatus.ACTIVE, null))).containsExactly("PUBG 랭크 코칭", "R6 입문 강의");
        assertThat(titles(search(ProgramStatus.CLOSED, null))).containsExactly("Aim Training");
    }

    @Test
    @DisplayName("검색어는 제목과 멘토 닉네임을 대소문자 구분 없이 부분 일치로 찾는다")
    void searchWithKeyword() {
        assertThat(titles(search(null, "aim"))).containsExactly("Aim Training");
        assertThat(titles(search(null, "랭크"))).containsExactly("PUBG 랭크 코칭");
        assertThat(titles(search(null, "ALPHA"))).containsExactly("Aim Training", "PUBG 랭크 코칭");
        assertThat(titles(search(null, "BetaMent"))).containsExactly("R6 입문 강의");
        assertThat(titles(search(ProgramStatus.CLOSED, "alpha"))).containsExactly("Aim Training");
        assertThat(titles(search(ProgramStatus.ACTIVE, "aim"))).isEmpty();
    }

    @Test
    @DisplayName("빈 검색어는 기존처럼 전체와 일치한다")
    void searchWithEmptyKeyword() {
        assertThat(titles(search(null, ""))).hasSize(3);
    }

    @Test
    @DisplayName("count 쿼리 결과가 페이지 전체 건수와 일치한다")
    void countQueryMatchesContent() {
        // 페이지 크기를 1로 두어 Spring Data가 count 쿼리를 실제로 실행하게 한다
        PageRequest firstOfOne = PageRequest.of(0, 1, Sort.by("title"));

        Page<MentoringProgram> all = mentoringProgramRepository.searchProgramsForAdmin(null, null, firstOfOne);
        assertThat(all.getTotalElements()).isEqualTo(3);
        assertThat(all.getContent()).hasSize(1);

        assertThat(mentoringProgramRepository.searchProgramsForAdmin(ProgramStatus.ACTIVE, null, firstOfOne)
                .getTotalElements()).isEqualTo(2);
        assertThat(mentoringProgramRepository.searchProgramsForAdmin(null, "ALPHA", firstOfOne)
                .getTotalElements()).isEqualTo(2);
        assertThat(mentoringProgramRepository.searchProgramsForAdmin(ProgramStatus.CLOSED, "alpha", firstOfOne)
                .getTotalElements()).isEqualTo(1);
    }

    private Page<MentoringProgram> search(ProgramStatus status, String keyword) {
        return mentoringProgramRepository.searchProgramsForAdmin(status, keyword, PageRequest.of(0, 20, Sort.by("title")));
    }

    private static java.util.List<String> titles(Page<MentoringProgram> page) {
        return page.getContent().stream().map(MentoringProgram::getTitle).toList();
    }

    private MentorProfile createMentorProfile(String nickname) {
        User user = User.createLocal(
                nickname + "_" + UUID.randomUUID() + "@gamerin.com",
                nickname.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 8),
                nickname,
                "Pass!"
        );
        user.setProfile(UserProfile.createDefault(user));
        User saved = userRepository.save(user);
        // @MapsId로 PK를 공유하므로 같은 트랜잭션의 managed User로 저장
        return transactionTemplate.execute(status -> {
            MentorProfile profile = new MentorProfile();
            profile.setUser(userRepository.findById(saved.getId()).orElseThrow());
            profile.setAbout("소개");
            return mentorProfileRepository.save(profile);
        });
    }

    private void createProgram(MentorProfile mentor, String title, ProgramStatus status) {
        MentoringProgram program = new MentoringProgram();
        program.setMentor(mentor);
        program.setGameName("PUBG");
        program.setTitle(title);
        program.setContent("내용");
        program.setPrice(1000L);
        program.setStatus(status);
        mentoringProgramRepository.save(program);
    }

    private record PostgresSchema(String url, String username, String password, String schema) {
        private static PostgresSchema create() {
            String url = requiredEnvironment("MENTORING_POSTGRES_TEST_URL", false);
            String username = requiredEnvironment("MENTORING_POSTGRES_TEST_USERNAME", false);
            String password = requiredEnvironment("MENTORING_POSTGRES_TEST_PASSWORD", true);
            String schema = "admin_program_search_" + UUID.randomUUID().toString().replace("-", "");

            executeSchemaStatement(url, username, password, "create schema " + schema);
            return new PostgresSchema(url, username, password, schema);
        }

        private void close() {
            executeSchemaStatement(url, username, password, "drop schema if exists " + schema + " cascade");
        }

        private static String requiredEnvironment(String name, boolean emptyAllowed) {
            String value = System.getenv(name);
            if (value == null || (!emptyAllowed && value.isBlank())) {
                throw new IllegalStateException(name + " must be set for PostgreSQL test.");
            }
            return value;
        }

        private static void executeSchemaStatement(
                String url,
                String username,
                String password,
                String sql
        ) {
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException error) {
                throw new IllegalStateException("Could not prepare PostgreSQL test schema.", error);
            }
        }
    }
}
