package com.gamerin.backend.domain.mentoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.gamerin.backend.domain.mentoring.dto.request.MentoringProgramUpdateRequest;
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
import com.gamerin.backend.domain.notification.service.NotificationCommandService;
import com.gamerin.backend.domain.user.entity.TransactionType;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import org.junit.jupiter.api.DisplayName;

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

        @Mock
        private NotificationCommandService notificationCommandService;

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
                                settlementProcessor,
                                notificationCommandService);
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
                                pageable);

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
                                UUID.randomUUID());
                MentoringProgram program = application.getProgram();
                program.setStatus(ProgramStatus.CLOSED);

                when(mentoringProgramRepository.findByIdAndDeletedAtIsNull(program.getId()))
                                .thenReturn(Optional.of(program));

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
                when(userRepository.findActiveByIdForUpdate(menteeId)).thenReturn(Optional.of(application.getMentee()));

                assertThatThrownBy(() -> mentoringService.applyToProgram(
                                CustomUserPrincipal.from(application.getMentee()),
                                new MentoringApplicationRequest(programId, "message")))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(error -> {
                                        ResponseStatusException ex = (ResponseStatusException) error;
                                        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
                                        assertThat(ex.getReason()).isEqualTo("마감된 프로그램에는 신청할 수 없습니다.");
                                });

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
                                ApplicationStatus.FINISHED);

                when(userRepository.findActiveByIdForUpdate(menteeId)).thenReturn(Optional.of(application.getMentee()));
                when(mentoringProgramRepository.findByIdForUpdate(programId)).thenReturn(Optional.of(program));
                when(mentoringApplicationRepository.existsByMenteeIdAndProgramIdAndStatusIn(
                                menteeId,
                                programId,
                                reapplyBlockingStatuses)).thenReturn(true);

                assertThatThrownBy(() -> mentoringService.applyToProgram(
                                CustomUserPrincipal.from(application.getMentee()),
                                new MentoringApplicationRequest(programId, "message")))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(error -> {
                                        ResponseStatusException ex = (ResponseStatusException) error;
                                        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
                                        assertThat(ex.getReason()).isEqualTo("이미 진행 중인 신청 내역이 있습니다.");
                                });

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

        @Test
        @DisplayName("ESCROW_HELD 신청이 있는 프로그램 삭제 시 409 CONFLICT가 발생하고 소프트 삭제가 일어나지 않는다")
        void deleteProgramRejectedWhenEscrowHeldApplicationExists() {
                UUID mentorId = UUID.randomUUID();
                UUID programId = UUID.randomUUID();
                MentoringApplication application = application(mentorId, UUID.randomUUID(), programId,
                                UUID.randomUUID());
                MentoringProgram program = application.getProgram();

                when(mentoringProgramRepository.findByIdForUpdate(programId)).thenReturn(Optional.of(program));
                // ESCROW_HELD 신청이 존재하는 상황 (APPLIED/ACCEPTED/ONGOING/FINISHED 상태)
                when(mentoringApplicationRepository.existsByProgramIdAndPaymentStatus(eq(programId),
                                eq(PaymentStatus.ESCROW_HELD))).thenReturn(true);

                assertThatThrownBy(() -> mentoringService.deleteProgram(
                                CustomUserPrincipal.from(application.getProgram().getMentor().getUser()),
                                programId))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(error -> {
                                        ResponseStatusException ex = (ResponseStatusException) error;
                                        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
                                        assertThat(ex.getReason()).contains("진행 중인 멘토링 신청이 있어 삭제할 수 없습니다.");
                                });

                // 소프트 삭제가 일어나지 않았으므로 deleted_at이 null이어야 함
                assertThat(program.getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("완료·정산된 신청만 있는 프로그램은 소프트 삭제되고 deleted_at이 기록된다")
        void deleteProgramSoftDeletesWhenNoEscrowHeldApplicationExists() {
                UUID mentorId = UUID.randomUUID();
                UUID programId = UUID.randomUUID();
                MentoringApplication application = application(mentorId, UUID.randomUUID(), programId,
                                UUID.randomUUID());
                MentoringProgram program = application.getProgram();

                when(mentoringProgramRepository.findByIdForUpdate(programId)).thenReturn(Optional.of(program));
                // ESCROW_HELD 신청 없음 (전부 SETTLED 또는 REFUNDED)
                when(mentoringApplicationRepository.existsByProgramIdAndPaymentStatus(
                                eq(programId), eq(PaymentStatus.ESCROW_HELD))).thenReturn(false);

                assertThatCode(() -> mentoringService.deleteProgram(
                                CustomUserPrincipal.from(application.getProgram().getMentor().getUser()),
                                programId))
                                .doesNotThrowAnyException();

                // 물리 삭제가 아닌 소프트 삭제 — deleted_at이 현재 시각으로 기록됨
                assertThat(program.getDeletedAt()).isNotNull();
                assertThat(program.isDeleted()).isTrue();
        }

        @Test
        @DisplayName("소프트 삭제된 프로그램에 신청 시 NOT_FOUND 404가 반환된다")
        void applyToSoftDeletedProgramReturns404() {
                UUID programId = UUID.randomUUID();

                User mentee = savedUser(UUID.randomUUID(), "mentee", "Mentee");
                when(userRepository.findActiveByIdForUpdate(mentee.getId())).thenReturn(Optional.of(mentee));
                // findByIdForUpdate에 deletedAt IS NULL 조건이 있으므로 소프트 삭제된 프로그램은 빈 값 반환
                when(mentoringProgramRepository.findByIdForUpdate(programId)).thenReturn(Optional.empty());

                assertThatThrownBy(() -> mentoringService.applyToProgram(
                                CustomUserPrincipal.from(mentee),
                                new MentoringApplicationRequest(programId, "message")))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(error -> {
                                        ResponseStatusException ex = (ResponseStatusException) error;
                                        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
                                });
        }

        @Test
        @DisplayName("탈퇴한 멘티는 프로그램을 잠그거나 마일리지를 차감하기 전에 404로 거부된다")
        void applyByWithdrawnMenteeIsRejectedBeforeLockingProgram() {
                User mentee = savedUser(UUID.randomUUID(), "mentee", "Mentee");
                when(userRepository.findActiveByIdForUpdate(mentee.getId())).thenReturn(Optional.empty());

                assertThatThrownBy(() -> mentoringService.applyToProgram(
                                CustomUserPrincipal.from(mentee),
                                new MentoringApplicationRequest(UUID.randomUUID(), "message")))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                                                .isEqualTo(HttpStatus.NOT_FOUND.value()));

                verify(mentoringProgramRepository, never()).findByIdForUpdate(any());
                verify(mileageService, never()).useMileage(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("소프트 삭제된 프로그램 상세 조회 시 404 NOT_FOUND가 발생한다")
        void getProgramDetailThrowsNotFoundWhenProgramIsSoftDeleted() {
                UUID programId = UUID.randomUUID();

                when(mentoringProgramRepository.findByIdAndDeletedAtIsNull(programId))
                                .thenReturn(Optional.empty());

                assertThatThrownBy(() -> mentoringService.getProgramDetail(programId))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(error -> {
                                        ResponseStatusException ex = (ResponseStatusException) error;
                                        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
                                        assertThat(ex.getReason()).isEqualTo("프로그램을 찾을 수 없습니다.");
                                });
        }

        @Test
        @DisplayName("소프트 삭제된 프로그램 수정 시 404 NOT_FOUND가 발생한다")
        void updateProgramThrowsNotFoundWhenProgramIsSoftDeleted() {
                UUID programId = UUID.randomUUID();
                User mentorUser = savedUser(UUID.randomUUID(), "mentor", "Mentor");
                MentoringProgramUpdateRequest request = new MentoringProgramUpdateRequest(
                                "제목", "내용", "시간", 1000L, ProgramStatus.ACTIVE, List.of("tag"));

                when(mentoringProgramRepository.findByIdAndDeletedAtIsNull(programId))
                                .thenReturn(Optional.empty());

                assertThatThrownBy(() -> mentoringService.updateProgram(
                                CustomUserPrincipal.from(mentorUser), programId, request))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(error -> {
                                        ResponseStatusException ex = (ResponseStatusException) error;
                                        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
                                        assertThat(ex.getReason()).isEqualTo("프로그램을 찾을 수 없습니다.");
                                });
        }

        @Test
        @DisplayName("[F2] 신청 성공 시 신청 엔티티가 먼저 저장되고 MENTORING_PAY 원장의 referenceId로 연결된다")
        void applyToProgramSavesApplicationBeforeChargingAndConnectsReferenceId() {
                UUID mentorId = UUID.randomUUID();
                UUID menteeId = UUID.randomUUID();
                UUID programId = UUID.randomUUID();
                MentoringApplication app = application(mentorId, menteeId, programId, UUID.randomUUID());
                MentoringProgram prog = app.getProgram();
                prog.setStatus(ProgramStatus.ACTIVE);
                prog.setPrice(15000L);

                when(mentoringProgramRepository.findByIdForUpdate(programId)).thenReturn(Optional.of(prog));
                when(mentoringApplicationRepository.existsByMenteeIdAndProgramIdAndStatusIn(any(), any(), any()))
                                .thenReturn(false);
                when(userRepository.findActiveByIdForUpdate(menteeId)).thenReturn(Optional.of(app.getMentee()));
                when(mentoringApplicationRepository.save(any(MentoringApplication.class))).thenAnswer(invocation -> {
                        MentoringApplication a = invocation.getArgument(0);
                        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
                        return a;
                });

                MentoringApplicationResponse response = mentoringService.applyToProgram(
                                CustomUserPrincipal.from(app.getMentee()),
                                new MentoringApplicationRequest(programId, "잘 부탁드립니다."));

                // useMileage의 마지막 인자(referenceId)로 생성된 application.id가 전달되었는지 검증
                verify(mileageService, times(1)).useMileage(
                                eq(app.getMentee()),
                                eq(15000L),
                                eq(TransactionType.MENTORING_PAY),
                                anyString(),
                                eq(response.id()));
        }

        @Test
        @DisplayName("[F3] completeMentoring 시 10% 수수료가 공제된 금액이 지급되고 fee_amount가 저장된다")
        void completeMentoringDeductsFeeAndPaysMentor() {
                UUID mentorId = UUID.randomUUID();
                UUID menteeId = UUID.randomUUID();
                UUID programId = UUID.randomUUID();
                UUID appId = UUID.randomUUID();
                MentoringApplication app = application(mentorId, menteeId, programId, appId);
                app.setStatus(ApplicationStatus.ONGOING);
                app.setAppliedMileage(20000L);
                app.setFeeRateBp(1000);

                when(mentoringApplicationRepository.findByIdForUpdate(appId)).thenReturn(Optional.of(app));
                when(mentorProfileRepository.findByIdForUpdate(app.getProgram().getMentor().getId()))
                                .thenReturn(Optional.of(app.getProgram().getMentor()));

                mentoringService.completeMentoring(CustomUserPrincipal.from(app.getMentee()), appId);

                // 20,000P에 대한 10% 수수료: fee = 2,000P, 멘토 실지급 = 18,000P
                assertThat(app.getFeeAmount()).isEqualTo(2000L);
                assertThat(app.getStatus()).isEqualTo(ApplicationStatus.COMPLETED);
                assertThat(app.getPaymentStatus()).isEqualTo(PaymentStatus.SETTLED);

                verify(mileageService, times(1)).addMileage(
                                eq(app.getProgram().getMentor().getUser()),
                                eq(18000L),
                                eq(TransactionType.SETTLEMENT),
                                anyString(),
                                eq(appId));
        }

        @Test
        @DisplayName("[F4] 신청 취소 시 전액 환불되며 수수료 계산이 발생하지 않는다 (fee_amount는 null)")
        void cancelApplicationRefundsFullMileageWithoutFeeCalculation() {
                UUID mentorId = UUID.randomUUID();
                UUID menteeId = UUID.randomUUID();
                UUID programId = UUID.randomUUID();
                UUID appId = UUID.randomUUID();
                MentoringApplication app = application(mentorId, menteeId, programId, appId);
                app.setStatus(ApplicationStatus.APPLIED);
                app.setAppliedMileage(20000L);

                when(mentoringApplicationRepository.findByIdForUpdate(appId)).thenReturn(Optional.of(app));

                mentoringService.cancelApplication(CustomUserPrincipal.from(app.getMentee()), appId);

                // 취소 환불은 20,000P 전액 환불, feeAmount는 null 유지
                assertThat(app.getFeeAmount()).isNull();
                assertThat(app.getStatus()).isEqualTo(ApplicationStatus.CANCELLED);
                assertThat(app.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);

                verify(mileageService, times(1)).addMileage(
                                eq(app.getMentee()),
                                eq(20000L),
                                eq(TransactionType.MENTORING_REFUND),
                                anyString(),
                                eq(appId));
        }
}
