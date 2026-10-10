package com.gamerin.backend.domain.mentoring.integration;

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
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 멘토링 프로그램 소프트 삭제 시 deleted_at 설정, 활성 단건 조회(404) 격리,
 * 그리고 1차 캐시 flush/clear 후 엔티티 영속성을 검증하는 JPA 회귀 테스트입니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MentoringProgramSoftDeleteJpaTest {

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
    private EntityManager entityManager;

    @Test
    @DisplayName("완료/정산된 신청과 리뷰가 있는 프로그램을 삭제하면 소프트 삭제만 수행되고 신청과 리뷰가 보존된다")
    void settledApplicationAndReviewPreservedAfterSoftDelete() {
        User mentorUser = createTestUser("mentor");
        MentorProfile mentorProfile = createMentorProfile(mentorUser);
        User menteeUser = createTestUser("mentee");

        MentoringProgram program = createProgram(mentorProfile, "배틀그라운드 코칭", 1000L);

        MentoringApplication application = new MentoringApplication();
        application.setProgram(program);
        application.setMentee(menteeUser);
        application.setAppliedMileage(1000L);
        application.setStatus(ApplicationStatus.COMPLETED);
        application.setPaymentStatus(PaymentStatus.SETTLED);
        application = mentoringApplicationRepository.save(application);

        MentoringReview review = new MentoringReview();
        review.setApplication(application);
        review.setMentor(mentorProfile);
        review.setMentee(menteeUser);
        review.setRating(5);
        review.setContent("정말 유익한 코칭이었습니다!");
        review = mentoringReviewRepository.save(review);

        CustomUserPrincipal mentorPrincipal = CustomUserPrincipal.from(mentorUser);
        mentoringService.deleteProgram(mentorPrincipal, program.getId());

        // 영속성 컨텍스트(1차 캐시)를 비워 실제 DB SELECT 쿼리를 강제함
        entityManager.flush();
        entityManager.clear();

        // 검증 1: 프로그램 deleted_at이 기록됨
        MentoringProgram deletedProgram = mentoringProgramRepository.findById(program.getId()).orElseThrow();
        assertThat(deletedProgram.getDeletedAt()).isNotNull();

        // 검증 2: 활성 단건 조회는 404 (empty)
        assertThat(mentoringProgramRepository.findByIdAndDeletedAtIsNull(program.getId())).isEmpty();

        // 검증 3: 신청 및 리뷰 엔티티가 보존됨
        assertThat(mentoringApplicationRepository.findById(application.getId())).isPresent();
        assertThat(mentoringReviewRepository.findById(review.getId())).isPresent();
    }

    @Test
    @DisplayName("진행 중인 에스크로(ESCROW_HELD) 신청이 있는 경우 삭제 시 409 CONFLICT가 발생하고 deleted_at이 기록되지 않는다")
    void escrowHeldApplicationBlocksProgramDelete() {
        User mentorUser = createTestUser("mentor");
        MentorProfile mentorProfile = createMentorProfile(mentorUser);
        User menteeUser = createTestUser("mentee");

        MentoringProgram program = createProgram(mentorProfile, "배틀그라운드 코칭", 1000L);

        MentoringApplication application = new MentoringApplication();
        application.setProgram(program);
        application.setMentee(menteeUser);
        application.setAppliedMileage(1000L);
        application.setStatus(ApplicationStatus.APPLIED);
        application.setPaymentStatus(PaymentStatus.ESCROW_HELD);
        mentoringApplicationRepository.save(application);

        entityManager.flush();
        entityManager.clear();

        CustomUserPrincipal mentorPrincipal = CustomUserPrincipal.from(mentorUser);

        assertThatThrownBy(() -> mentoringService.deleteProgram(mentorPrincipal, program.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException ex = (ResponseStatusException) error;
                    assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
                });

        entityManager.flush();
        entityManager.clear();

        MentoringProgram preservedProgram = mentoringProgramRepository.findById(program.getId()).orElseThrow();
        assertThat(preservedProgram.getDeletedAt()).isNull();
    }

    private User createTestUser(String prefix) {
        User user = User.createLocal(
                prefix + "_" + UUID.randomUUID() + "@gamerin.com",
                prefix + "_" + UUID.randomUUID().toString().substring(0, 8),
                prefix,
                "Password123!"
        );
        user.setProfile(UserProfile.createDefault(user));
        return userRepository.save(user);
    }

    private MentorProfile createMentorProfile(User user) {
        MentorProfile mentorProfile = new MentorProfile();
        mentorProfile.setUser(user);
        mentorProfile.setAbout("멘토 소개");
        return mentorProfileRepository.save(mentorProfile);
    }

    private MentoringProgram createProgram(MentorProfile mentor, String title, Long price) {
        MentoringProgram program = new MentoringProgram();
        program.setMentor(mentor);
        program.setGameName("PUBG");
        program.setTitle(title);
        program.setContent("상세 내용");
        program.setPrice(price);
        program.setStatus(ProgramStatus.ACTIVE);
        return mentoringProgramRepository.save(program);
    }
}