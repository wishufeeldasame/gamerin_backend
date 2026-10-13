package com.gamerin.backend.domain.mentoring.integration;

import com.gamerin.backend.domain.admin.service.AdminMentoringService;
import com.gamerin.backend.domain.mentoring.entity.*;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.mentoring.service.MentoringService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class MentoringSettlementConcurrencyTest {

    @Autowired
    private MentoringService mentoringService;

    @Autowired
    private AdminMentoringService adminMentoringService;

    @Autowired
    private MentorProfileRepository mentorProfileRepository;

    @Autowired
    private MentoringApplicationRepository mentoringApplicationRepository;

    @Autowired
    private MentoringProgramRepository mentoringProgramRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("[정산 정합성 검증] 수동 확정과 관리자 강제 정산이 각각 수행될 때 멘토 프로필 락 조회를 통해 menteeCount가 2로 정상 누적된다")

    void sequentialOrLockedSettlementIncrementsMenteeCountProperly() {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
    
            // 1. Given: 멘토 1명, 관리자 1명, 멘티 2명 및 신청 2건 생성
            Object[] fixtures = tx.execute(status -> {
                User admin = userRepository.save(User.createLocal("admin@test.com", "admin_concur", "관리자",  "pw"));
                User mentorUser = userRepository.save(User.createLocal("mentor@test.com", "mentor_concur", "멘토", "pw"));
                User mentee1 = userRepository.save(User.createLocal("mentee1@test.com", "mentee1_concur", "멘티1", "pw"));
                User mentee2 = userRepository.save(User.createLocal("mentee2@test.com", "mentee2_concur", "멘티2", "pw"));
    
                MentorProfile mentorProfile = new MentorProfile();
                mentorProfile.setUser(mentorUser);
                mentorProfile.setMenteeCount(0);
                mentorProfile = mentorProfileRepository.save(mentorProfile);
    
                MentoringProgram program = new MentoringProgram();
                program.setMentor(mentorProfile);
                program.setTitle("정산 테스트 프로그램");
                program.setGameName("LOL");
                program.setPrice(10000L);
                program = mentoringProgramRepository.save(program);
    
                MentoringApplication appA = new MentoringApplication();
                appA.setProgram(program);
                appA.setMentee(mentee1);
                appA.setAppliedMileage(10000L);
                appA.setStatus(ApplicationStatus.ONGOING);
                appA.setPaymentStatus(PaymentStatus.ESCROW_HELD);
                appA = mentoringApplicationRepository.save(appA);
    
                MentoringApplication appB = new MentoringApplication();
                appB.setProgram(program);
                appB.setMentee(mentee2);
                appB.setAppliedMileage(10000L);
                appB.setStatus(ApplicationStatus.ONGOING);
                appB.setPaymentStatus(PaymentStatus.ESCROW_HELD);
                appB = mentoringApplicationRepository.save(appB);
    
                return new Object[]{admin.getId(), mentorUser.getId(), mentee1, appA.getId(), appB.getId()};
            });
    
            UUID adminId = (UUID) fixtures[0];
            UUID mentorUserId = (UUID) fixtures[1];
            User mentee1 = (User) fixtures[2];
            UUID appAId = (UUID) fixtures[3];
            UUID appBId = (UUID) fixtures[4];
    
            // 2. When: 
            // 1번 정산: 멘티의 수동 완료 확정 (트랜잭션 1 커밋)
            mentoringService.completeMentoring(CustomUserPrincipal.from(mentee1), appAId);

            // 2번 정산: 관리자의 강제 정산 (트랜잭션 2 커밋) - 멘토 프로필 락 조회를 통해 1번에서 갱신된 menteeCount(1)를 읽어 +1 수행
            adminMentoringService.forceSettle(adminId, appBId, "강제 정산");

            // 3. Then: 최종 DB의 mentorProfile menteeCount 확인
            Integer finalMenteeCount = tx.execute(status -> {
                MentorProfile profile = mentorProfileRepository.findById(mentorUserId).orElseThrow();
                return profile.getMenteeCount();
            });

            assertThat(finalMenteeCount)
                    .as("두 정산 경로가 모두 멘토 프로필을 정상 갱신하여 menteeCount는 2여야 한다.")
                    .isEqualTo(2);
        }
}