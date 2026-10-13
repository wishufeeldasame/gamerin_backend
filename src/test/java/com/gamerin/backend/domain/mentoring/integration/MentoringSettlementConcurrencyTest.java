package com.gamerin.backend.domain.mentoring.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamerin.backend.domain.admin.service.AdminMentoringService;
import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.PaymentStatus;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.mentoring.service.MentoringService;
import com.gamerin.backend.domain.mentoring.service.SettlementProcessor;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

/**
 * 같은 멘토에 대해 세 정산 경로(수동 완료·관리자 강제 정산·자동 정산)를 동시에 실행했을 때
 * 1) menteeCount 증가분이 유실되지 않고(#83),
 * 2) 세 경로의 수수료·지급액이 일치하는지(#90 F3) 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class MentoringSettlementConcurrencyTest {

    private static final long APPLIED = 10_000L;

    @Autowired private MentoringService mentoringService;
    @Autowired private AdminMentoringService adminMentoringService;
    @Autowired private SettlementProcessor settlementProcessor;
    @Autowired private MentorProfileRepository mentorProfileRepository;
    @Autowired private MentoringApplicationRepository mentoringApplicationRepository;
    @Autowired private MentoringProgramRepository mentoringProgramRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.execute("DELETE FROM admin_audit_logs");
        jdbc.execute("DELETE FROM notifications");
        jdbc.execute("DELETE FROM mileage_transactions");
        jdbc.execute("DELETE FROM mileage_wallets");
        jdbc.execute("DELETE FROM mentoring_applications");
        jdbc.execute("DELETE FROM mentoring_programs");
        jdbc.execute("DELETE FROM mentor_profiles");
        jdbc.execute("DELETE FROM users");
    }

    private User newUser(String prefix) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.createLocal(prefix + id + "@test.com", prefix + id, prefix, "pw"));
    }

    private MentoringApplication newApplication(MentoringProgram program, User mentee, ApplicationStatus status) {
        MentoringApplication application = new MentoringApplication();
        application.setProgram(program);
        application.setMentee(mentee);
        application.setAppliedMileage(APPLIED);
        application.setStatus(status);
        application.setPaymentStatus(PaymentStatus.ESCROW_HELD);
        return mentoringApplicationRepository.save(application);
    }

    @Test
    @DisplayName("세 정산 경로가 동시에 실행돼도 menteeCount가 유실되지 않고 수수료·지급액이 모두 일치한다")
    void threeSettlementPathsRunConcurrentlyWithoutLosingMenteeCount() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        record Fixture(UUID adminId, UUID mentorId, User mentee1, UUID manualId, UUID forceId, UUID autoId) {}

        Fixture fixture = tx.execute(status -> {
            User admin = newUser("admin");
            User mentorUser = newUser("mentor");
            User mentee1 = newUser("mentee1");
            User mentee2 = newUser("mentee2");
            User mentee3 = newUser("mentee3");

            MentorProfile mentorProfile = new MentorProfile();
            mentorProfile.setUser(mentorUser);
            mentorProfile.setMenteeCount(0);
            mentorProfile = mentorProfileRepository.save(mentorProfile);

            MentoringProgram program = new MentoringProgram();
            program.setMentor(mentorProfile);
            program.setTitle("동시 정산 테스트");
            program.setGameName("LOL");
            program.setPrice(APPLIED);
            program = mentoringProgramRepository.save(program);

            MentoringApplication manual = newApplication(program, mentee1, ApplicationStatus.ONGOING);
            MentoringApplication force = newApplication(program, mentee2, ApplicationStatus.ONGOING);
            MentoringApplication auto = newApplication(program, mentee3, ApplicationStatus.FINISHED);

            return new Fixture(admin.getId(), mentorUser.getId(), mentee1, manual.getId(), force.getId(), auto.getId());
        });

        // 자동 정산 대상이 되도록 updated_at을 8일 전으로 되돌린다.
        jdbc.update("update mentoring_applications set updated_at = ? where id = ?",
                OffsetDateTime.now().minusDays(8), fixture.autoId());
        OffsetDateTime threshold = OffsetDateTime.now().minusDays(7);

        List<Callable<Void>> tasks = List.of(
                () -> {
                    mentoringService.completeMentoring(CustomUserPrincipal.from(fixture.mentee1()), fixture.manualId());
                    return null;
                },
                () -> {
                    adminMentoringService.forceSettle(fixture.adminId(), fixture.forceId(), "동시 정산 테스트");
                    return null;
                },
                () -> {
                    settlementProcessor.processSingleSettlement(fixture.autoId(), threshold);
                    return null;
                });

        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = tasks.stream()
                .map(task -> executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }))
                .toList();

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS); // 하나라도 실패하면 예외로 테스트 실패
        }
        executor.shutdown();

        // 1) #83: 세 건 모두 반영되어 3이어야 한다. (유실되면 1 또는 2)
        Integer menteeCount = jdbc.queryForObject(
                "select mentee_count from mentor_profiles where user_id = ?", Integer.class, fixture.mentorId());
        assertThat(menteeCount).as("세 정산이 모두 반영되어야 한다").isEqualTo(3);

        // 2) #90 F3: 세 경로 모두 10% 수수료(1,000P)를 기록하고 9,000P씩 지급한다.
        for (UUID applicationId : List.of(fixture.manualId(), fixture.forceId(), fixture.autoId())) {
            assertThat(jdbc.queryForObject(
                    "select fee_amount from mentoring_applications where id = ?", Long.class, applicationId))
                    .isEqualTo(1_000L);
            assertThat(jdbc.queryForObject(
                    "select amount from mileage_transactions where reference_id = ? and type = 'SETTLEMENT'",
                    Long.class, applicationId))
                    .isEqualTo(9_000L);
        }
        assertThat(jdbc.queryForObject(
                "select balance from mileage_wallets where user_id = ?", Long.class, fixture.mentorId()))
                .isEqualTo(27_000L);
    }
}