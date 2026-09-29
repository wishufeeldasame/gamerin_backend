package com.gamerin.backend.domain.mentoring.integration;

import com.gamerin.backend.domain.mentoring.dto.request.MentoringApplicationRequest;
import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.MentoringReview;
import com.gamerin.backend.domain.mentoring.entity.PaymentStatus;
import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringReviewRepository;
import com.gamerin.backend.domain.mentoring.service.MentoringService;
import com.gamerin.backend.domain.user.entity.MileageWallet;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.MileageWalletRepository;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 PostgreSQL + Flyway V1~V27 스키마 기반 통합 테스트.
 * - V9의 ON DELETE CASCADE가 적용된 실제 환경에서 소프트 삭제 시 신청/리뷰가 보존되는지 검증
 * - 삭제와 신규 신청 동시 요청 시 CountDownLatch로 시작하여 비관적 락 직렬화 및 최종 DB 데이터 정합성 검증
 */
@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "MENTORING_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MentoringProgramPostgresConcurrencyTest {

    private static PostgresSchema postgresSchema;

    @Autowired
    private MentoringService mentoringService;
    @Autowired
    private MentoringProgramRepository mentoringProgramRepository;
    @Autowired
    private MentoringApplicationRepository mentoringApplicationRepository;
    @Autowired
    private MentoringReviewRepository mentoringReviewRepository;
    @Autowired
    private MentorProfileRepository mentorProfileRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MileageWalletRepository mileageWalletRepository;

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

    @AfterAll
    static void removeTemporarySchema() {
        if (postgresSchema != null) {
            postgresSchema.close();
        }
    }

    @Test
    @DisplayName("실제 PostgreSQL Flyway V9 CASCADE 환경에서 소프트 삭제 시 신청 및 리뷰가 보존된다")
    void softDeletePreservesApplicationsAndReviewsInPostgres() {
        User mentor = createTestUser("mentor");
        MentorProfile profile = createMentorProfile(mentor);
        User mentee = createTestUser("mentee");

        MentoringProgram program = createProgram(profile, "배틀그라운드 코칭", 1000L);

        MentoringApplication app = new MentoringApplication();
        app.setProgram(program);
        app.setMentee(mentee);
        app.setAppliedMileage(1000L);
        app.setStatus(ApplicationStatus.COMPLETED);
        app.setPaymentStatus(PaymentStatus.SETTLED);
        app = mentoringApplicationRepository.save(app);

        MentoringReview review = new MentoringReview();
        review.setApplication(app);
        review.setMentor(profile);
        review.setMentee(mentee);
        review.setRating(5);
        review.setContent("우수 코칭");
        review = mentoringReviewRepository.save(review);

        // 삭제 실행
        mentoringService.deleteProgram(CustomUserPrincipal.from(mentor), program.getId());

        // 검증: 소프트 삭제 적용 및 실제 PostgreSQL에서 CASCADE 삭제되지 않고 온전히 유지됨
        assertThat(mentoringProgramRepository.findById(program.getId()).orElseThrow().getDeletedAt())
                .isNotNull();
        assertThat(mentoringApplicationRepository.findById(app.getId())).isPresent();
        assertThat(mentoringReviewRepository.findById(review.getId())).isPresent();
    }

    @Test
    @DisplayName("삭제와 신규 신청 동시 요청 시 CountDownLatch로 병렬 실행되어 정확히 직렬화되고 최종 DB 데이터가 정합성을 유지한다")
    void concurrentDeleteAndApplySerializedStrictly() throws Exception {
        User mentor = createTestUser("mentor");
        MentorProfile profile = createMentorProfile(mentor);
        User mentee = createTestUser("mentee");

        MileageWallet wallet = new MileageWallet();
        wallet.setUser(mentee);
        wallet.setBalance(50000L);
        mileageWalletRepository.save(wallet);

        MentoringProgram program = createProgram(profile, "배틀그라운드 코칭", 1000L);
        UUID programId = program.getId();

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        AtomicReference<Throwable> deleteError = new AtomicReference<>();
        AtomicReference<Throwable> applyError = new AtomicReference<>();

        try {
            Future<?> deleteFuture = executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    mentoringService.deleteProgram(CustomUserPrincipal.from(mentor), programId);
                } catch (Throwable t) {
                    deleteError.set(t);
                }
            });

            Future<?> applyFuture = executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    mentoringService.applyToProgram(
                            CustomUserPrincipal.from(mentee),
                            new MentoringApplicationRequest(programId, "신청")
                    );
                } catch (Throwable t) {
                    applyError.set(t);
                }
            });

            // 모든 스레드가 준비 상태에 도달했는지 검증 (5초 타임아웃)
            boolean ready = readyLatch.await(5, TimeUnit.SECONDS);
            assertThat(ready).as("모든 동시성 작업 스레드가 5초 이내에 준비되어야 합니다").isTrue();

            startLatch.countDown(); // 동시 시작 신호 방출

            deleteFuture.get(10, TimeUnit.SECONDS);
            applyFuture.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow(); // 작업 종료 후 안전하게 스레드 풀 강제 해제
        }

        // 직렬화 결과 및 최종 DB 데이터 상태 정합성 검증
        MentoringProgram finalProgram = mentoringProgramRepository.findById(programId).orElseThrow();
        long escrowApplicationCount = mentoringApplicationRepository
                .countByProgramIdAndPaymentStatus(programId, PaymentStatus.ESCROW_HELD);

        if (deleteError.get() == null) {
            // 케이스 1: 삭제가 먼저 락 획득 -> 삭제 성공, 신청 실패(404 NOT_FOUND)
            assertThat(applyError.get()).isInstanceOf(ResponseStatusException.class);
            assertThat(((ResponseStatusException) applyError.get()).getStatusCode().value())
                    .isEqualTo(HttpStatus.NOT_FOUND.value());

            // 최종 DB 상태 검증: 프로그램은 소프트 삭제 완료, 에스크로 신청 건수는 0건이어야 함
            assertThat(finalProgram.getDeletedAt()).isNotNull();
            assertThat(escrowApplicationCount).isEqualTo(0L);
        } else {
            // 케이스 2: 신청이 먼저 락 획득 -> 신청 성공, 삭제 실패(409 CONFLICT)
            assertThat(deleteError.get()).isInstanceOf(ResponseStatusException.class);
            assertThat(((ResponseStatusException) deleteError.get()).getStatusCode().value())
                    .isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(applyError.get()).isNull();

            // 최종 DB 상태 검증: 프로그램은 활성 상태(deletedAt == null), 에스크로 신청 건수는 정확히 1건이어야 함
            assertThat(finalProgram.getDeletedAt()).isNull();
            assertThat(escrowApplicationCount).isEqualTo(1L);
        }
    }

    private User createTestUser(String prefix) {
        User user = User.createLocal(
                prefix + "_" + UUID.randomUUID() + "@gamerin.com",
                prefix + "_" + UUID.randomUUID().toString().substring(0, 8),
                prefix,
                "Pass!"
        );
        user.setProfile(UserProfile.createDefault(user));
        return userRepository.save(user);
    }

    private MentorProfile createMentorProfile(User user) {
        MentorProfile profile = new MentorProfile();
        profile.setUser(user);
        profile.setAbout("소개");
        return mentorProfileRepository.save(profile);
    }

    private MentoringProgram createProgram(MentorProfile mentor, String title, Long price) {
        MentoringProgram program = new MentoringProgram();
        program.setMentor(mentor);
        program.setGameName("PUBG");
        program.setTitle(title);
        program.setContent("내용");
        program.setPrice(price);
        program.setStatus(ProgramStatus.ACTIVE);
        return mentoringProgramRepository.save(program);
    }

    private record PostgresSchema(String url, String username, String password, String schema) {
        private static PostgresSchema create() {
            String url = requiredEnvironment("MENTORING_POSTGRES_TEST_URL", false);
            String username = requiredEnvironment("MENTORING_POSTGRES_TEST_USERNAME", false);
            String password = requiredEnvironment("MENTORING_POSTGRES_TEST_PASSWORD", true);
            String schema = "mentoring_concurrency_" + UUID.randomUUID().toString().replace("-", "");

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