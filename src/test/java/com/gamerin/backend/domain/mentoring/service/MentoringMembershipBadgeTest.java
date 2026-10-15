package com.gamerin.backend.domain.mentoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.game.model.GameType;
import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.mentoring.dto.request.MentorRegistrationRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringApplicationRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringProgramRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringProgramUpdateRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringReviewRequest;
import com.gamerin.backend.domain.mentoring.dto.response.MentorProfileResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringApplicationResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringProgramResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringReviewResponse;
import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentorStatus;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.MentoringReview;
import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringReviewRepository;
import com.gamerin.backend.domain.notification.service.NotificationCommandService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@ExtendWith(MockitoExtension.class)
class MentoringMembershipBadgeTest {

    @Mock private MentorProfileRepository mentorProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private MentoringProgramRepository mentoringProgramRepository;
    @Mock private MentoringApplicationRepository mentoringApplicationRepository;
    @Mock private MentoringReviewRepository mentoringReviewRepository;
    @Mock private MileageService mileageService;
    @Mock private SettlementProcessor settlementProcessor;
    @Mock private NotificationCommandService notificationCommandService;
    @Mock private MembershipService membershipService;
    @InjectMocks private MentoringService service;

    private final PageRequest pageable = PageRequest.of(0, 20);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void publicAndOwnMentorProfilesUseMentorsMembership(boolean active) {
        MentorProfile mentor = application().getProgram().getMentor();
        when(mentorProfileRepository.findById(mentor.getId())).thenReturn(Optional.of(mentor));
        when(membershipService.isActive(mentor.getId())).thenReturn(active);

        assertThat(service.getMentorProfile(mentor.getId()).membershipBadge()).isEqualTo(active);
        assertThat(service.getMyMentorProfile(CustomUserPrincipal.from(mentor.getUser())).membershipBadge())
                .isEqualTo(active);
    }

    @Test
    void missingOwnProfileDoesNotQueryMembership() {
        User user = user("nonmentor");
        when(mentorProfileRepository.findById(user.getId())).thenReturn(Optional.empty());

        assertThat(service.getMyMentorProfile(CustomUserPrincipal.from(user))).isNull();

        verifyNoInteractions(membershipService);
    }

    @Test
    void mentorRegistrationIncludesCurrentBadge() {
        User user = user("newmentor");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(mentorProfileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(membershipService.isActive(user.getId())).thenReturn(true);

        MentorProfileResponse response = service.registerMentor(CustomUserPrincipal.from(user),
                new MentorRegistrationRequest("coaching"));

        assertThat(response.membershipBadge()).isTrue();
        assertThat(response.status()).isEqualTo(MentorStatus.PENDING_APPROVAL);
    }

    @Test
    void programListBatchesDistinctMentorsAndPreservesOrderAndPagination() {
        MentoringProgram first = application().getProgram();
        MentoringProgram second = application().getProgram();
        MentoringProgram repeatedMentor = application().getProgram();
        repeatedMentor.setMentor(first.getMentor());
        when(mentoringProgramRepository.findByFilters(null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(first, second, repeatedMentor), pageable, 31));
        Set<UUID> ids = Set.of(first.getMentor().getId(), second.getMentor().getId());
        when(membershipService.findActiveUserIds(ids)).thenReturn(Set.of(second.getMentor().getId()));

        Page<MentoringProgramResponse> response = service.getPrograms(null, null, pageable);

        assertThat(response.getContent()).extracting(MentoringProgramResponse::membershipBadge)
                .containsExactly(false, true, false);
        assertThat(response.getContent()).extracting(MentoringProgramResponse::id)
                .containsExactly(first.getId(), second.getId(), repeatedMentor.getId());
        assertThat(response.getTotalElements()).isEqualTo(31);
        verify(membershipService).findActiveUserIds(ids);
        verifyNoMoreInteractions(membershipService);
    }

    @Test
    void emptyProgramListSkipsMembership() {
        when(mentoringProgramRepository.findByFilters(null, null, pageable)).thenReturn(Page.empty(pageable));

        assertThat(service.getPrograms(null, null, pageable)).isEmpty();

        verifyNoInteractions(membershipService);
    }

    @Test
    void programDetailAndUpdateIncludeMentorsBadge() {
        MentoringProgram program = application().getProgram();
        UUID mentorId = program.getMentor().getId();
        when(mentoringProgramRepository.findByIdAndDeletedAtIsNull(program.getId()))
                .thenReturn(Optional.of(program));
        when(membershipService.isActive(mentorId)).thenReturn(true);

        assertThat(service.getProgramDetail(program.getId()).membershipBadge()).isTrue();
        MentoringProgramResponse updated = service.updateProgram(CustomUserPrincipal.from(program.getMentor().getUser()),
                program.getId(), new MentoringProgramUpdateRequest("updated", "content", "evenings", 2000L,
                        ProgramStatus.ACTIVE, List.of("tag")));

        assertThat(updated.membershipBadge()).isTrue();
        assertThat(updated.title()).isEqualTo("updated");
        assertThat(updated.price()).isEqualTo(2000L);
    }

    @Test
    void programRegistrationIncludesMentorsBadge() {
        MentorProfile mentor = application().getProgram().getMentor();
        mentor.setStatus(MentorStatus.ACTIVE);
        when(mentorProfileRepository.findById(mentor.getId())).thenReturn(Optional.of(mentor));
        when(mentoringProgramRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(membershipService.isActive(mentor.getId())).thenReturn(true);

        MentoringProgramResponse response = service.registerProgram(CustomUserPrincipal.from(mentor.getUser()),
                new MentoringProgramRequest(GameType.PUBG, "program", "content", "evenings", 1000L, List.of()));

        assertThat(response.membershipBadge()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void applicationListsBatchBothRolesAndKeepTheirBadgesIndependent(boolean asMentor) {
        MentoringApplication first = application();
        MentoringApplication second = application();
        if (asMentor) {
            second.setProgram(first.getProgram());
        } else {
            second.setMentee(first.getMentee());
        }
        Page<MentoringApplication> page = new PageImpl<>(List.of(first, second), pageable, 2);
        CustomUserPrincipal principal = CustomUserPrincipal.from(asMentor
                ? first.getProgram().getMentor().getUser() : first.getMentee());
        if (asMentor) {
            when(mentoringApplicationRepository.findByProgramMentorId(principal.getUserId(), pageable)).thenReturn(page);
        } else {
            when(mentoringApplicationRepository.findByMenteeId(principal.getUserId(), pageable)).thenReturn(page);
        }
        when(mentoringReviewRepository.findReviewedApplicationIds(List.of(first.getId(), second.getId())))
                .thenReturn(List.of(first.getId()));
        UUID mentorId = first.getProgram().getMentor().getId();
        Set<UUID> participants = new HashSet<>(List.of(mentorId, second.getProgram().getMentor().getId(),
                first.getMentee().getId(), second.getMentee().getId()));
        when(membershipService.findActiveUserIds(participants)).thenReturn(Set.of(mentorId, second.getMentee().getId()));

        Page<MentoringApplicationResponse> response = asMentor
                ? service.getMyApplicationsAsMentor(principal, pageable)
                : service.getMyApplicationsAsMentee(principal, pageable);

        assertThat(response.getContent()).extracting(MentoringApplicationResponse::mentorMembershipBadge)
                .containsExactly(true, asMentor);
        assertThat(response.getContent()).extracting(MentoringApplicationResponse::menteeMembershipBadge)
                .containsExactly(!asMentor, true);
        assertThat(response.getContent()).extracting(MentoringApplicationResponse::reviewed).containsExactly(true, false);
        verify(membershipService).findActiveUserIds(participants);
        verifyNoMoreInteractions(membershipService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void emptyApplicationListsSkipMembershipAndReviewQueries(boolean asMentor) {
        CustomUserPrincipal principal = CustomUserPrincipal.from(user("empty"));
        if (asMentor) {
            when(mentoringApplicationRepository.findByProgramMentorId(principal.getUserId(), pageable))
                    .thenReturn(Page.empty(pageable));
            assertThat(service.getMyApplicationsAsMentor(principal, pageable)).isEmpty();
        } else {
            when(mentoringApplicationRepository.findByMenteeId(principal.getUserId(), pageable))
                    .thenReturn(Page.empty(pageable));
            assertThat(service.getMyApplicationsAsMentee(principal, pageable)).isEmpty();
        }

        verifyNoInteractions(membershipService, mentoringReviewRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"apply", "cancel", "accept", "reject", "start", "finish", "complete"})
    void applicationMutationResponsesIncludeBothParticipantBadges(String action) {
        MentoringApplication application = application();
        MentorProfile mentor = application.getProgram().getMentor();
        UUID menteeId = application.getMentee().getId();
        CustomUserPrincipal mentorPrincipal = CustomUserPrincipal.from(mentor.getUser());
        CustomUserPrincipal menteePrincipal = CustomUserPrincipal.from(application.getMentee());
        Set<UUID> participants = Set.of(mentor.getId(), menteeId);
        when(membershipService.findActiveUserIds(participants)).thenReturn(Set.of(menteeId));
        if (action.equals("apply")) {
            when(mentoringProgramRepository.findByIdForUpdate(application.getProgram().getId()))
                    .thenReturn(Optional.of(application.getProgram()));
            when(userRepository.findById(menteeId)).thenReturn(Optional.of(application.getMentee()));
            when(mentoringApplicationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        } else {
            when(mentoringApplicationRepository.findByIdForUpdate(application.getId()))
                    .thenReturn(Optional.of(application));
        }
        application.setStatus(switch (action) {
            case "start" -> ApplicationStatus.ACCEPTED;
            case "finish", "complete" -> ApplicationStatus.ONGOING;
            default -> ApplicationStatus.APPLIED;
        });
        if (action.equals("complete")) {
            when(mentorProfileRepository.findByIdForUpdate(mentor.getId())).thenReturn(Optional.of(mentor));
        }

        MentoringApplicationResponse response = switch (action) {
            case "apply" -> service.applyToProgram(menteePrincipal,
                    new MentoringApplicationRequest(application.getProgram().getId(), "message"));
            case "cancel" -> service.cancelApplication(menteePrincipal, application.getId());
            case "accept" -> service.acceptApplication(mentorPrincipal, application.getId());
            case "reject" -> service.rejectApplication(mentorPrincipal, application.getId());
            case "start" -> service.startMentoring(mentorPrincipal, application.getId());
            case "finish" -> service.finishMentoring(mentorPrincipal, application.getId());
            case "complete" -> service.completeMentoring(menteePrincipal, application.getId());
            default -> throw new AssertionError(action);
        };

        assertThat(response.mentorMembershipBadge()).isFalse();
        assertThat(response.menteeMembershipBadge()).isTrue();
        verify(membershipService).findActiveUserIds(participants);
        verifyNoMoreInteractions(membershipService);
    }

    @Test
    void reviewsBatchTheirWritersRatherThanReviewedMentor() {
        MentoringReview first = review(application());
        MentoringReview second = review(application());
        UUID mentorId = first.getMentor().getId();
        when(mentoringReviewRepository.findByMentorUserId(mentorId, pageable))
                .thenReturn(new PageImpl<>(List.of(first, second), pageable, 2));
        Set<UUID> writers = Set.of(first.getMentee().getId(), second.getMentee().getId());
        when(membershipService.findActiveUserIds(writers)).thenReturn(Set.of(second.getMentee().getId()));

        Page<MentoringReviewResponse> response = service.getMentorReviews(mentorId, pageable);

        assertThat(response.getContent()).extracting(MentoringReviewResponse::membershipBadge)
                .containsExactly(false, true);
        verify(membershipService).findActiveUserIds(writers);
        verifyNoMoreInteractions(membershipService);
    }

    @Test
    void emptyReviewsSkipMembership() {
        UUID mentorId = UUID.randomUUID();
        when(mentoringReviewRepository.findByMentorUserId(mentorId, pageable)).thenReturn(Page.empty(pageable));

        assertThat(service.getMentorReviews(mentorId, pageable)).isEmpty();

        verifyNoInteractions(membershipService);
    }

    @Test
    void reviewCreationUsesWritersMembership() {
        MentoringApplication application = application();
        application.setStatus(ApplicationStatus.COMPLETED);
        MentorProfile mentor = application.getProgram().getMentor();
        when(mentoringApplicationRepository.findByIdForUpdate(application.getId())).thenReturn(Optional.of(application));
        when(mentorProfileRepository.findByIdForUpdate(mentor.getId())).thenReturn(Optional.of(mentor));
        when(mentoringReviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(membershipService.isActive(application.getMentee().getId())).thenReturn(true);

        MentoringReviewResponse response = service.createReview(CustomUserPrincipal.from(application.getMentee()),
                new MentoringReviewRequest(application.getId(), 5, "helpful"));

        assertThat(response.membershipBadge()).isTrue();
        verify(membershipService, never()).isActive(mentor.getId());
    }

    @Test
    void unauthorizedCancellationStopsBeforeMembershipAndPaymentSideEffects() {
        MentoringApplication application = application();
        application.setStatus(ApplicationStatus.APPLIED);
        when(mentoringApplicationRepository.findByIdForUpdate(application.getId()))
                .thenReturn(Optional.of(application));

        assertThatThrownBy(() -> service.cancelApplication(CustomUserPrincipal.from(user("stranger")),
                application.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(403));

        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        verifyNoInteractions(membershipService, mileageService, notificationCommandService);
    }

    private MentoringReview review(MentoringApplication application) {
        MentoringReview review = new MentoringReview();
        review.setId(UUID.randomUUID());
        review.setApplication(application);
        review.setMentor(application.getProgram().getMentor());
        review.setMentee(application.getMentee());
        review.setRating(5);
        return review;
    }

    private MentoringApplication application() {
        MentorProfile mentor = new MentorProfile();
        mentor.setUser(user("mentor"));
        mentor.setStatus(MentorStatus.ACTIVE);
        MentoringProgram program = new MentoringProgram();
        program.setId(UUID.randomUUID());
        program.setMentor(mentor);
        program.setTitle("coaching");
        program.setPrice(1000L);
        program.setStatus(ProgramStatus.ACTIVE);
        MentoringApplication application = new MentoringApplication();
        application.setId(UUID.randomUUID());
        application.setProgram(program);
        application.setMentee(user("mentee"));
        application.setAppliedMileage(1000L);
        return application;
    }

    private User user(String handle) {
        User user = User.createLocal(handle + "@example.com", handle, handle, "encoded-password");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
