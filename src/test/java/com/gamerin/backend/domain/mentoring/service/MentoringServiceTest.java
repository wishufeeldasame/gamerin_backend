package com.gamerin.backend.domain.mentoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringApplicationRequest;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringApplicationResponse;
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

        @Mock
        private MembershipService membershipService;

        @InjectMocks
        private MentoringService mentoringService;

        private final List<ApplicationStatus> reapplyBlockingStatuses = List.of(
                        ApplicationStatus.APPLIED,
                        ApplicationStatus.ACCEPTED,
                        ApplicationStatus.ONGOING,
                        ApplicationStatus.FINISHED);

        @Test
        void getMentorProfileThrowsNotFoundWhenProfileDoesNotExist() {
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
                // 멘티 화면에서는 요율·수수료 관련 정보가 노출되지 않아야 함 (멘티 가격 그대로 유지)
                assertThat(item.feeRateBp()).isNull();
                assertThat(item.feeAmount()).isNull();
                assertThat(item.payoutAmount()).isNull();
        }

        @Test
        void getProgramDetailIncludesStatus() {
                MentoringApplication application = application(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID());
                MentoringProgram program = application.getProgram();
                program.setStatus(ProgramStatus.ACTIVE);

                when(mentoringProgramRepository.findByIdAndDeletedAtIsNull(program.getId()))
                                .thenReturn(Optional.of(program));

                var response = mentoringService.getProgramDetail(program.getId());

                assertThat(response.id()).isEqualTo(program.getId());
                assertThat(response.status()).isEqualTo(ProgramStatus.ACTIVE);
        }

        @Test
        void applyToProgramRejectsWhenProgramIsClosed() {
                MentoringApplication application = application(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID());
                MentoringProgram program = application.getProgram();
                program.setStatus(ProgramStatus.CLOSED);

                when(userRepository.findActiveByIdForUpdate(application.getMentee().getId()))
                                .thenReturn(Optional.of(application.getMentee()));
                when(mentoringProgramRepository.findByIdForUpdate(program.getId()))
                                .thenReturn(Optional.of(program));

                assertThatThrownBy(() -> mentoringService.applyToProgram(
                                CustomUserPrincipal.from(application.getMentee()),
                                new MentoringApplicationRequest(program.getId(), "message")))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(error -> {
                                        ResponseStatusException ex = (ResponseStatusException) error;
                                        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
                                        assertThat(ex.getReason()).isEqualTo("마감된 프로그램에는 신청할 수 없습니다.");
                                });
        }

        @Test
        void applyToProgramRejectsDuplicateActiveApplicationBeforeChargingMileage() {
                UUID mentorId = UUID.randomUUID();
                UUID menteeId = UUID.randomUUID();
                UUID programId = UUID.randomUUID();
                MentoringApplication application = application(mentorId, menteeId, programId, UUID.randomUUID());
                MentoringProgram program = application.getProgram();
                program.setStatus(ProgramStatus.ACTIVE);

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

                verify(mileageService, times(1)).useMileage(
                                eq(app.getMentee()),
                                eq(15000L),
                                eq(TransactionType.MENTORING_PAY),
                                anyString(),
                                eq(response.id()));
        }

        @Test
        @DisplayName("[F2/KP-C2] 멘토가 멤버십 활성 상태일 때 신청하면 500bp(5%) 요율로 저장된다")
        void applyToProgramSnapshotsMembershipFeeRate500BpWhenMentorIsActive() {
                UUID mentorId = UUID.randomUUID();
                UUID menteeId = UUID.randomUUID();
                UUID programId = UUID.randomUUID();
                MentoringApplication app = application(mentorId, menteeId, programId, UUID.randomUUID());
                MentoringProgram prog = app.getProgram();
                prog.setStatus(ProgramStatus.ACTIVE);
                prog.setPrice(10000L);

                when(mentoringProgramRepository.findByIdForUpdate(programId)).thenReturn(Optional.of(prog));
                when(mentoringApplicationRepository.existsByMenteeIdAndProgramIdAndStatusIn(any(), any(), any()))
                                .thenReturn(false);
                when(userRepository.findActiveByIdForUpdate(menteeId)).thenReturn(Optional.of(app.getMentee()));
                when(membershipService.isActive(mentorId)).thenReturn(true);

                AtomicReference<MentoringApplication> savedRef = new AtomicReference<>();
                when(mentoringApplicationRepository.save(any(MentoringApplication.class))).thenAnswer(invocation -> {
                        MentoringApplication a = invocation.getArgument(0);
                        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
                        savedRef.set(a);
                        return a;
                });

                mentoringService.applyToProgram(
                                CustomUserPrincipal.from(app.getMentee()),
                                new MentoringApplicationRequest(programId, "멤버십 멘토 신청"));

                assertThat(savedRef.get()).isNotNull();
                assertThat(savedRef.get().getFeeRateBp()).isEqualTo(MentoringApplication.MEMBERSHIP_FEE_RATE_BP); // 500
        }

        @Test
        @DisplayName("[F2/KP-C2] 멘토가 일반(비가입) 상태일 때 신청하면 1000bp(10%) 요율로 저장된다")
        void applyToProgramSnapshotsDefaultFeeRate1000BpWhenMentorIsInactive() {
                UUID mentorId = UUID.randomUUID();
                UUID menteeId = UUID.randomUUID();
                UUID programId = UUID.randomUUID();
                MentoringApplication app = application(mentorId, menteeId, programId, UUID.randomUUID());
                MentoringProgram prog = app.getProgram();
                prog.setStatus(ProgramStatus.ACTIVE);
                prog.setPrice(10000L);

                when(mentoringProgramRepository.findByIdForUpdate(programId)).thenReturn(Optional.of(prog));
                when(mentoringApplicationRepository.existsByMenteeIdAndProgramIdAndStatusIn(any(), any(), any()))
                                .thenReturn(false);
                when(userRepository.findActiveByIdForUpdate(menteeId)).thenReturn(Optional.of(app.getMentee()));
                when(membershipService.isActive(mentorId)).thenReturn(false);

                AtomicReference<MentoringApplication> savedRef = new AtomicReference<>();
                when(mentoringApplicationRepository.save(any(MentoringApplication.class))).thenAnswer(invocation -> {
                        MentoringApplication a = invocation.getArgument(0);
                        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
                        savedRef.set(a);
                        return a;
                });

                mentoringService.applyToProgram(
                                CustomUserPrincipal.from(app.getMentee()),
                                new MentoringApplicationRequest(programId, "일반 멘토 신청"));

                assertThat(savedRef.get()).isNotNull();
                assertThat(savedRef.get().getFeeRateBp()).isEqualTo(MentoringApplication.DEFAULT_FEE_RATE_BP); // 1000
        }

        @Test
        @DisplayName("[KP-C2] 신청 당시 멤버십(500bp)이었으나 이후 정산 시점에 멘토 멤버십이 만료되어도 5% 수수료가 유지된다")
        void completeMentoringPreserves5PercentFeeEvenIfMembershipExpired() {
                UUID mentorId = UUID.randomUUID();
                UUID menteeId = UUID.randomUUID();
                UUID programId = UUID.randomUUID();
                UUID appId = UUID.randomUUID();
                MentoringApplication app = application(mentorId, menteeId, programId, appId);
                app.setStatus(ApplicationStatus.ONGOING);
                app.setAppliedMileage(20000L);
                app.setFeeRateBp(MentoringApplication.MEMBERSHIP_FEE_RATE_BP); // 500bp (5%)

                when(mentoringApplicationRepository.findByIdForUpdate(appId)).thenReturn(Optional.of(app));
                when(mentorProfileRepository.findByIdForUpdate(mentorId))
                                .thenReturn(Optional.of(app.getProgram().getMentor()));

                mentoringService.completeMentoring(CustomUserPrincipal.from(app.getMentee()), appId);

                // 수수료: 20000 * 500 / 10000 = 1000, 멘토 수령액: 19000
                assertThat(app.getFeeAmount()).isEqualTo(1000L);
                verify(mileageService, times(1)).addMileage(
                                eq(app.getProgram().getMentor().getUser()),
                                eq(19000L),
                                eq(TransactionType.SETTLEMENT),
                                anyString(),
                                eq(appId));
        }

        @Test
        @DisplayName("[KP-C2] 신청 당시 일반(1000bp)이었으나 정산 시점에 멘토가 멤버십에 가입해도 10% 수수료가 유지된다")
        void completeMentoringPreserves10PercentFeeEvenIfMentorJoinedMembershipLater() {
                UUID mentorId = UUID.randomUUID();
                UUID menteeId = UUID.randomUUID();
                UUID programId = UUID.randomUUID();
                UUID appId = UUID.randomUUID();
                MentoringApplication app = application(mentorId, menteeId, programId, appId);
                app.setStatus(ApplicationStatus.ONGOING);
                app.setAppliedMileage(20000L);
                app.setFeeRateBp(MentoringApplication.DEFAULT_FEE_RATE_BP); // 1000bp (10%)

                when(mentoringApplicationRepository.findByIdForUpdate(appId)).thenReturn(Optional.of(app));
                when(mentorProfileRepository.findByIdForUpdate(mentorId))
                                .thenReturn(Optional.of(app.getProgram().getMentor()));

                mentoringService.completeMentoring(CustomUserPrincipal.from(app.getMentee()), appId);

                // 수수료: 20000 * 1000 / 10000 = 2000, 멘토 수령액: 18000
                assertThat(app.getFeeAmount()).isEqualTo(2000L);
                verify(mileageService, times(1)).addMileage(
                                eq(app.getProgram().getMentor().getUser()),
                                eq(18000L),
                                eq(TransactionType.SETTLEMENT),
                                anyString(),
                                eq(appId));
        }

        @Test
        @DisplayName("[F3] 이미 정산 완료(COMPLETED)된 신청은 completeMentoring 재호출 시 예외가 발생한다")
        void completeMentoringPreventsDuplicateSettlement() {
                UUID mentorId = UUID.randomUUID();
                UUID menteeId = UUID.randomUUID();
                UUID programId = UUID.randomUUID();
                UUID appId = UUID.randomUUID();
                MentoringApplication app = application(mentorId, menteeId, programId, appId);
                app.setStatus(ApplicationStatus.COMPLETED); // 이미 완료 상태

                when(mentoringApplicationRepository.findByIdForUpdate(appId)).thenReturn(Optional.of(app));

                assertThatThrownBy(() -> mentoringService.completeMentoring(CustomUserPrincipal.from(app.getMentee()),
                                appId))
                                .isInstanceOf(ResponseStatusException.class)
                                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                                                .isEqualTo(HttpStatus.CONFLICT));

                verify(mileageService, never()).addMileage(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("[F5/KP-C2] 멘토가 받은 신청 목록 조회 시 요율·수수료·수령액과 비가입 안내가 제공된다")
        void getMyApplicationsAsMentorProvidesFeeAndPayoutDetails() {
                UUID mentorId = UUID.randomUUID();
                UUID menteeId = UUID.randomUUID();
                UUID programId = UUID.randomUUID();
                UUID appId = UUID.randomUUID();
                MentoringApplication app = application(mentorId, menteeId, programId, appId);
                app.setAppliedMileage(10000L);
                app.setFeeRateBp(1000); // 비가입 일반 요율
                PageRequest pageable = PageRequest.of(0, 10);

                when(mentoringApplicationRepository.findByProgramMentorId(mentorId, pageable))
                                .thenReturn(new PageImpl<>(List.of(app), pageable, 1));
                when(mentoringReviewRepository.findReviewedApplicationIds(anyList()))
                                .thenReturn(Collections.emptyList());

                Page<MentoringApplicationResponse> result = mentoringService.getMyApplicationsAsMentor(
                                CustomUserPrincipal.from(app.getProgram().getMentor().getUser()),
                                pageable);

                MentoringApplicationResponse item = result.getContent().get(0);
                assertThat(item.feeRateBp()).isEqualTo(1000);
                assertThat(item.feeAmount()).isEqualTo(1000L);
                assertThat(item.payoutAmount()).isEqualTo(9000L);
                assertThat(item.nonMemberNotice()).isTrue();
                // 상수를 직접 참조하여 검증
                assertThat(item.feeNotice()).isEqualTo(MentoringApplicationResponse.NON_MEMBER_NOTICE_MESSAGE);
        }

        private MentoringApplication application(UUID mentorId, UUID menteeId, UUID programId, UUID applicationId) {
                User mentorUser = User.createLocal(
                                "mentor@example.com",
                                "mentorHandle",
                                "mentorNickname",
                                "password");
                ReflectionTestUtils.setField(mentorUser, "id", mentorId);

                MentorProfile mentor = new MentorProfile();
                mentor.setUser(mentorUser);
                mentor.setRatingAvg(BigDecimal.valueOf(4.5));
                mentor.setReviewCount(10);

                MentoringProgram program = new MentoringProgram();
                ReflectionTestUtils.setField(program, "id", programId);
                program.setMentor(mentor);
                program.setPrice(10000L);
                program.setTitle("PUBG coaching");
                program.setContent("coaching content");

                User mentee = User.createLocal(
                                "mentee@example.com",
                                "menteeHandle",
                                "menteeNickname",
                                "password");
                ReflectionTestUtils.setField(mentee, "id", menteeId);

                MentoringApplication application = new MentoringApplication();
                ReflectionTestUtils.setField(application, "id", applicationId);
                application.setProgram(program);
                application.setMentee(mentee);
                application.setAppliedMileage(10000L);
                application.setStatus(ApplicationStatus.APPLIED);
                application.setPaymentStatus(PaymentStatus.PENDING);
                return application;
        }
}