package com.gamerin.backend.domain.report.repository;

import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.report.entity.Report;
import com.gamerin.backend.domain.report.entity.ReportReasonCode;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MENTORING 신고는 신청(application) ID로 접수되므로, 관리자 프로그램 신고 건수는
 * 신청의 프로그램 기준으로 집계되어야 한다 (#80).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReportRepositoryMentoringCountTest {

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private MentoringProgramRepository mentoringProgramRepository;

    @Autowired
    private MentoringApplicationRepository mentoringApplicationRepository;

    @Autowired
    private MentorProfileRepository mentorProfileRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("신청 건에 접수된 MENTORING 신고가 해당 프로그램의 신고 건수로 집계된다")
    void countMentoringReportsByProgramIdIn_aggregatesByApplicationProgram() {
        User reporter = createUser("reporter");
        User mentee = createUser("mentee");
        MentorProfile mentor = createMentorProfile(createUser("mentor"));

        MentoringProgram programA = createProgram(mentor, "A");
        MentoringProgram programB = createProgram(mentor, "B");
        MentoringProgram programWithoutReports = createProgram(mentor, "C");

        MentoringApplication appA1 = createApplication(programA, mentee);
        MentoringApplication appA2 = createApplication(programA, mentee);
        MentoringApplication appB = createApplication(programB, mentee);
        createApplication(programWithoutReports, mentee);

        saveReport(reporter, ReportTargetType.MENTORING, appA1.getId());
        saveReport(createUser("reporter2"), ReportTargetType.MENTORING, appA1.getId());
        saveReport(reporter, ReportTargetType.MENTORING, appA2.getId());
        saveReport(reporter, ReportTargetType.MENTORING, appB.getId());
        // 집계 대상이 아닌 신고: 다른 대상 유형, 프로그램 ID를 직접 대상으로 한 신고
        saveReport(reporter, ReportTargetType.POST, appA1.getId());
        saveReport(reporter, ReportTargetType.MENTORING, programA.getId());

        entityManager.flush();
        entityManager.clear();

        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : reportRepository.countMentoringReportsByProgramIdIn(
                List.of(programA.getId(), programB.getId(), programWithoutReports.getId()))) {
            counts.put((UUID) row[0], (Long) row[1]);
        }

        assertThat(counts)
                .containsEntry(programA.getId(), 3L)
                .containsEntry(programB.getId(), 1L)
                .doesNotContainKey(programWithoutReports.getId());
    }

    private User createUser(String prefix) {
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

    private MentoringProgram createProgram(MentorProfile mentor, String title) {
        MentoringProgram program = new MentoringProgram();
        program.setMentor(mentor);
        program.setGameName("PUBG");
        program.setTitle(title);
        program.setContent("상세 내용");
        program.setPrice(1000L);
        program.setStatus(ProgramStatus.ACTIVE);
        return mentoringProgramRepository.save(program);
    }

    private MentoringApplication createApplication(MentoringProgram program, User mentee) {
        MentoringApplication application = new MentoringApplication();
        application.setProgram(program);
        application.setMentee(mentee);
        application.setAppliedMileage(1000L);
        return mentoringApplicationRepository.save(application);
    }

    private void saveReport(User reporter, ReportTargetType targetType, UUID targetId) {
        reportRepository.save(Report.create(reporter, targetType, targetId, null, ReportReasonCode.SPAM, null));
    }
}
