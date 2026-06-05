package com.gamerin.backend.domain.mentoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.mentoring.dto.request.MentoringApplicationRequest;
import com.gamerin.backend.domain.mentoring.dto.response.MentorProfileResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringApplicationResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringProgramDetailResponse;
import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.PaymentStatus;
import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringReviewRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@ExtendWith(MockitoExtension.class)
class MentoringServiceTest {

    @Mock
    private MentorProfileRepository mentorProfileRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MentoringProgramRepository mentoringProgramRepository;

    @Mock
    private MentoringApplicationRepository mentoringApplicationRepository;

    @Mock
    private MentoringReviewRepository mentoringReviewRepository;

    @Mock
    private MileageService mileageService;

    @Mock
    private SettlementProcessor settlementProcessor;

    private MentoringService mentoringService;

    @BeforeEach
    void setUp() {
        mentoringService = new MentoringService(
                mentorProfileRepository,
                userRepository,
                mentoringProgramRepository,
                mentoringApplicationRepository,
                mentoringReviewRepository,
                mileageService,
                settlementProcessor
        );
    }

    @Test
    void getMyMentorProfileReturnsNullWhenUserIsNotMentor() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "mentee", "Mentee");

        when(mentorProfileRepository.findById(userId)).thenReturn(Optional.empty());

        MentorProfileResponse response = mentoringService.getMyMentorProfile(CustomUserPrincipal.from(user));

        assertThat(response).isNull();
    }

    @Test
    void getMentorProfileThrowsNotFoundWhenMentorDoesNotExist() {
        UUID mentorId = UUID.randomUUID();

        when(mentorProfileRepository.findById(mentorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mentoringService.getMentorProfile(mentorId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void menteeApplicationsIncludeParticipantIdsAndReviewedFlag() {
        UUID mentorId = UUID.randomUUID();
        UUID menteeId = UUID.randomUUID();
        UUID programId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        MentoringApplication application = application(mentorId, menteeId, programId, applicationId);
        PageRequest pageable = PageRequest.of(0, 20);

        when(mentoringApplicationRepository.findByMenteeId(menteeId, pageable))
                .thenReturn(new PageImpl<>(List.of(application), pageable, 1));
        when(mentoringReviewRepository.findReviewedApplicationIds(anyList()))
                .thenReturn(List.of(applicationId));

        Page<MentoringApplicationResponse> response = mentoringService.getMyApplicationsAsMentee(
                CustomUserPrincipal.from(application.getMentee()),
                pageable
        );

        MentoringApplicationResponse item = response.getContent().get(0);
        assertThat(item.id()).isEqualTo(applicationId);
        assertThat(item.programId()).isEqualTo(programId);
        assertThat(item.programTitle()).isEqualTo("PUBG coaching");
        assertThat(item.mentorId()).isEqualTo(mentorId);
        assertThat(item.menteeId()).isEqualTo(menteeId);
        assertThat(item.reviewed()).isTrue();
    }

    @Test
    void getProgramDetailIncludesStatus() {
        MentoringApplication application = application(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );
        MentoringProgram program = application.getProgram();
        program.setStatus(ProgramStatus.CLOSED);

        when(mentoringProgramRepository.findById(program.getId())).thenReturn(Optional.of(program));

        MentoringProgramDetailResponse response = mentoringService.getProgramDetail(program.getId());

        assertThat(response.status()).isEqualTo(ProgramStatus.CLOSED);
    }

    @Test
    void applyToProgramRejectsClosedProgramBeforeChargingMileage() {
        UUID mentorId = UUID.randomUUID();
        UUID menteeId = UUID.randomUUID();
        UUID programId = UUID.randomUUID();
        MentoringApplication application = application(mentorId, menteeId, programId, UUID.randomUUID());
        MentoringProgram program = application.getProgram();
        program.setStatus(ProgramStatus.CLOSED);

        when(mentoringProgramRepository.findByIdForUpdate(programId)).thenReturn(Optional.of(program));

        assertThatThrownBy(() -> mentoringService.applyToProgram(
                CustomUserPrincipal.from(application.getMentee()),
                new MentoringApplicationRequest(programId, "message")
        ))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("마감된 프로그램에는 신청할 수 없습니다.");

        verify(mileageService, never()).useMileage(any(), any(), any(), any(), any());
        verify(mentoringApplicationRepository, never()).save(any());
    }

    @Test
    void applyToProgramRejectsDuplicateActiveApplicationBeforeChargingMileage() {
        UUID mentorId = UUID.randomUUID();
        UUID menteeId = UUID.randomUUID();
        UUID programId = UUID.randomUUID();
        MentoringApplication application = application(mentorId, menteeId, programId, UUID.randomUUID());
        MentoringProgram program = application.getProgram();
        program.setStatus(ProgramStatus.ACTIVE);
        List<ApplicationStatus> reapplyBlockingStatuses = List.of(
                ApplicationStatus.APPLIED,
                ApplicationStatus.ACCEPTED,
                ApplicationStatus.ONGOING,
                ApplicationStatus.FINISHED
        );

        when(mentoringProgramRepository.findByIdForUpdate(programId)).thenReturn(Optional.of(program));
        when(mentoringApplicationRepository.existsByMenteeIdAndProgramIdAndStatusIn(
                menteeId,
                programId,
                reapplyBlockingStatuses
        )).thenReturn(true);

        assertThatThrownBy(() -> mentoringService.applyToProgram(
                CustomUserPrincipal.from(application.getMentee()),
                new MentoringApplicationRequest(programId, "message")
        ))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("이미 진행 중인 신청 내역이 있습니다.");

        verify(mileageService, never()).useMileage(any(), any(), any(), any(), any());
        verify(mentoringApplicationRepository, never()).save(any());
    }

    private MentoringApplication application(UUID mentorId, UUID menteeId, UUID programId, UUID applicationId) {
        User mentorUser = savedUser(mentorId, "mentor", "Mentor");
        User mentee = savedUser(menteeId, "mentee", "Mentee");

        MentorProfile mentorProfile = new MentorProfile();
        mentorProfile.setUser(mentorUser);
        mentorProfile.setAbout("about");

        MentoringProgram program = new MentoringProgram();
        program.setId(programId);
        program.setMentor(mentorProfile);
        program.setGameName("PUBG");
        program.setTitle("PUBG coaching");
        program.setContent("content");
        program.setPrice(1000L);

        MentoringApplication application = new MentoringApplication();
        application.setId(applicationId);
        application.setProgram(program);
        application.setMentee(mentee);
        application.setAppliedMileage(1000L);
        application.setStatus(ApplicationStatus.COMPLETED);
        application.setPaymentStatus(PaymentStatus.SETTLED);
        application.setMessage("message");
        return application;
    }

    private User savedUser(UUID id, String handle, String nickname) {
        User user = User.createLocal(handle + "@example.com", handle, nickname, "password");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
