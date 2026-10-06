package com.gamerin.backend.domain.admin.service;

import com.gamerin.backend.domain.admin.dto.response.AdminMentorResponse;
import com.gamerin.backend.domain.admin.dto.response.AdminMentoringProgramResponse;
import com.gamerin.backend.domain.admin.dto.response.AdminMentoringSummaryResponse;
import com.gamerin.backend.domain.admin.entity.AdminAuditLog;
import com.gamerin.backend.domain.admin.repository.AdminAuditLogRepository;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringApplicationResponse;
import com.gamerin.backend.domain.mentoring.entity.*;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.repository.ReportRepository;
import com.gamerin.backend.domain.user.entity.TransactionType;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminMentoringServiceTest {

    @Mock
    private MentoringApplicationRepository mentoringApplicationRepository;

    @Mock
    private MentoringProgramRepository mentoringProgramRepository;

    @Mock
    private MentorProfileRepository mentorProfileRepository;

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private MileageService mileageService;

    @Mock
    private AdminAuditLogRepository adminAuditLogRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AdminMentoringService adminMentoringService;

    private User admin;
    private UUID adminId;

    private User mentorUser;
    private UUID mentorUserId;
    private MentorProfile mentorProfile;

    private User menteeUser;
    private UUID menteeUserId;

    private MentoringProgram program;
    private UUID programId;

    private MentoringApplication application;
    private UUID applicationId;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        admin = User.createLocal("admin@gamerin.com", "admin01", "최고관리자", "encodedPw");
        ReflectionTestUtils.setField(admin, "id", adminId);

        mentorUserId = UUID.randomUUID();
        mentorUser = User.createLocal("mentor@gamerin.com", "pro_mentor", "프로멘토", "encodedPw");
        ReflectionTestUtils.setField(mentorUser, "id", mentorUserId);

        mentorProfile = new MentorProfile();
        mentorProfile.setUser(mentorUser);
        mentorProfile.setStatus(MentorStatus.PENDING_APPROVAL);
        mentorProfile.setAbout("LoL 챌린저 코칭 경력 5년");
        mentorProfile.setRatingAvg(BigDecimal.valueOf(4.85));
        mentorProfile.setReviewCount(12);
        mentorProfile.setMenteeCount(15);

        menteeUserId = UUID.randomUUID();
        menteeUser = User.createLocal("mentee@gamerin.com", "mentee01", "열심멘티", "encodedPw");
        ReflectionTestUtils.setField(menteeUser, "id", menteeUserId);

        programId = UUID.randomUUID();
        program = new MentoringProgram();
        program.setId(programId);
        program.setMentor(mentorProfile);
        program.setGameName("League of Legends");
        program.setTitle("다이아 탈출 맞춤 코칭");
        program.setContent("커리큘럼 상세 안내");
        program.setStatus(ProgramStatus.ACTIVE);
        program.setPrice(30000L);

        applicationId = UUID.randomUUID();
        application = new MentoringApplication();
        application.setId(applicationId);
        application.setProgram(program);
        application.setMentee(menteeUser);
        application.setAppliedMileage(30000L);
        application.setStatus(ApplicationStatus.APPLIED);
        application.setPaymentStatus(PaymentStatus.ESCROW_HELD);
    }

    // ================= [1. 멘토링 운영 요약 지표] =================

    @Test
    @DisplayName("멘토링 운영 요약 통계 지표(대기 멘토, 운영 프로그램, 월 세션, 에스크로 총액)를 정상 조회한다")
    void getMentoringSummary_success() {
        // given
        given(mentorProfileRepository.countByStatus(MentorStatus.PENDING_APPROVAL)).willReturn(5L);
        given(mentoringProgramRepository.countByStatusAndDeletedAtIsNull(ProgramStatus.ACTIVE)).willReturn(14L);
        given(mentoringApplicationRepository.countByCreatedAtAfter(any(OffsetDateTime.class))).willReturn(42L);
        given(mentoringApplicationRepository.sumEscrowHeldMileage()).willReturn(1260000L);

        // when
        AdminMentoringSummaryResponse summary = adminMentoringService.getMentoringSummary();

        // then
        assertThat(summary).isNotNull();
        assertThat(summary.pendingMentorCount()).isEqualTo(5L);
        assertThat(summary.activeProgramCount()).isEqualTo(14L);
        assertThat(summary.monthlySessionCount()).isEqualTo(42L);
        assertThat(summary.escrowHeldAmount()).isEqualTo(1260000L);
    }

    // ================= [2. 멘토 심사 및 관리] =================

    @Test
    @DisplayName("상태 필터 조건으로 멘토 목록을 페이징 조회할 수 있다")
    void getMentors_withStatus_success() {
        // given
        Pageable pageable = PageRequest.of(0, 10);
        Page<MentorProfile> page = new PageImpl<>(List.of(mentorProfile), pageable, 1);
        given(mentorProfileRepository.findByStatus(MentorStatus.PENDING_APPROVAL, pageable)).willReturn(page);

        // when
        Page<AdminMentorResponse> result = adminMentoringService.getMentors(MentorStatus.PENDING_APPROVAL, pageable);

        // then
        assertThat(result).hasSize(1);
        AdminMentorResponse item = result.getContent().get(0);
        assertThat(item.handle()).isEqualTo("pro_mentor");
        assertThat(item.status()).isEqualTo(MentorStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("상태 필터가 없으면 전체 멘토 목록을 페이징 조회한다")
    void getMentors_withoutStatus_success() {
        // given
        Pageable pageable = PageRequest.of(0, 10);
        Page<MentorProfile> page = new PageImpl<>(List.of(mentorProfile), pageable, 1);
        given(mentorProfileRepository.findAll(pageable)).willReturn(page);

        // when
        Page<AdminMentorResponse> result = adminMentoringService.getMentors(null, pageable);

        // then
        assertThat(result).hasSize(1);
        verify(mentorProfileRepository, times(1)).findAll(pageable);
    }

    @Test
    @DisplayName("멘토 신청을 승인하면 ACTIVE 상태로 변경되고 감사 로그가 적재된다")
    void approveMentor_success() {
        // given
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(mentorProfileRepository.findById(mentorUserId)).willReturn(Optional.of(mentorProfile));
        given(mentorProfileRepository.save(any(MentorProfile.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        AdminMentorResponse response = adminMentoringService.approveMentor(adminId, mentorUserId);

        // then
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(MentorStatus.ACTIVE);
        assertThat(mentorProfile.getStatus()).isEqualTo(MentorStatus.ACTIVE);

        verify(adminAuditLogRepository, times(1)).save(any(AdminAuditLog.class));
    }

    @Test
    @DisplayName("멘토 승인 시 대상 프로필이 없으면 404 예외가 발생한다")
    void approveMentor_notFound() {
        // given
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(mentorProfileRepository.findById(mentorUserId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminMentoringService.approveMentor(adminId, mentorUserId))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("멘토 신청을 반려/비활성화하면 INACTIVE 상태로 변경되고 감사 로그가 적재된다")
    void rejectMentor_success() {
        // given
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(mentorProfileRepository.findById(mentorUserId)).willReturn(Optional.of(mentorProfile));
        given(mentorProfileRepository.save(any(MentorProfile.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        AdminMentorResponse response = adminMentoringService.rejectMentor(adminId, mentorUserId, "서류 불충분");

        // then
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(MentorStatus.INACTIVE);
        assertThat(mentorProfile.getStatus()).isEqualTo(MentorStatus.INACTIVE);

        verify(adminAuditLogRepository, times(1)).save(any(AdminAuditLog.class));
    }

    // ================= [3. 멘토링 프로그램 관리] =================

    @Test
    @DisplayName("프로그램 목록 조회 시 세션 수, 평점, 신고 건수가 결합되어 반환된다")
    void getPrograms_success() {
        // given
        Pageable pageable = PageRequest.of(0, 10);
        Page<MentoringProgram> programPage = new PageImpl<>(List.of(program), pageable, 1);

        given(mentoringProgramRepository.searchProgramsForAdmin(ProgramStatus.ACTIVE, "코칭", pageable))
                .willReturn(programPage);
        given(mentoringApplicationRepository.countByProgramId(programId)).willReturn(8L);
        given(reportRepository.countByTargetTypeAndTargetId(ReportTargetType.MENTORING, programId))
                .willReturn(2L);

        // when
        Page<AdminMentoringProgramResponse> result = adminMentoringService.getPrograms(ProgramStatus.ACTIVE, "코칭", pageable);

        // then
        assertThat(result).hasSize(1);
        AdminMentoringProgramResponse res = result.getContent().get(0);
        assertThat(res.title()).isEqualTo("다이아 탈출 맞춤 코칭");
        assertThat(res.mentorNickname()).isEqualTo("프로멘토");
        assertThat(res.sessions()).isEqualTo(8L);
        assertThat(res.reports()).isEqualTo(2L);
        assertThat(res.rating()).isEqualTo(4.85);
    }

    @Test
    @DisplayName("프로그램 운영 상태(CLOSED 등)를 변경하면 상태가 갱신되고 감사 로그가 적재된다")
    void updateProgramStatus_success() {
        // given
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(mentoringProgramRepository.findById(programId)).willReturn(Optional.of(program));
        given(mentoringProgramRepository.save(any(MentoringProgram.class))).willAnswer(inv -> inv.getArgument(0));
        given(mentoringApplicationRepository.countByProgramId(programId)).willReturn(5L);
        given(reportRepository.countByTargetTypeAndTargetId(ReportTargetType.MENTORING, programId))
                .willReturn(0L);

        // when
        AdminMentoringProgramResponse res = adminMentoringService.updateProgramStatus(adminId, programId, ProgramStatus.CLOSED);

        // then
        assertThat(res.status()).isEqualTo(ProgramStatus.CLOSED);
        assertThat(program.getStatus()).isEqualTo(ProgramStatus.CLOSED);

        verify(adminAuditLogRepository, times(1)).save(any(AdminAuditLog.class));
    }

    @Test
    @DisplayName("프로그램 강제 숨김 처리 시 소프트 삭제되고 감사 로그가 적재된다")
    void hideProgram_success() {
        // given
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(mentoringProgramRepository.findById(programId)).willReturn(Optional.of(program));
        given(mentoringProgramRepository.save(any(MentoringProgram.class))).willAnswer(inv -> inv.getArgument(0));
        given(mentoringApplicationRepository.countByProgramId(programId)).willReturn(3L);
        given(reportRepository.countByTargetTypeAndTargetId(ReportTargetType.MENTORING, programId))
                .willReturn(1L);

        // when
        AdminMentoringProgramResponse res = adminMentoringService.hideProgram(adminId, programId, "허위 정보 등록 의심");

        // then
        assertThat(program.isDeleted()).isTrue();
        assertThat(program.getDeletedAt()).isNotNull();

        verify(adminAuditLogRepository, times(1)).save(any(AdminAuditLog.class));
    }

    @Test
    @DisplayName("이미 숨김 처리된 프로그램을 다시 숨김 시도하면 400 예외가 발생한다")
    void hideProgram_alreadyHidden_throwsBadRequest() {
        // given
        program.softDelete();
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(mentoringProgramRepository.findById(programId)).willReturn(Optional.of(program));

        // when & then
        assertThatThrownBy(() -> adminMentoringService.hideProgram(adminId, programId, "재숨김"))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ================= [4. 분쟁 에스크로 강제 개입] =================

    @Test
    @DisplayName("관리자 강제 환불 시 멘티에게 마일리지가 환불되고 상태가 취소/환불로 변경된다")
    void forceRefund_success() {
        // given
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(mentoringApplicationRepository.findByIdForUpdate(applicationId)).willReturn(Optional.of(application));

        // when
        MentoringApplicationResponse response = adminMentoringService.forceRefund(adminId, applicationId, "멘토 불참 신고 접수");

        // then
        assertThat(response).isNotNull();
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.CANCELLED);
        assertThat(application.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);

        // 멘티에게 전액 환불 마일리지 적립 호출 검증
        verify(mileageService, times(1)).addMileage(
                eq(menteeUser),
                eq(30000L),
                eq(TransactionType.MENTORING_REFUND),
                any(String.class),
                eq(applicationId)
        );

        verify(adminAuditLogRepository, times(1)).save(any(AdminAuditLog.class));
    }

    @Test
    @DisplayName("에스크로 보관(ESCROW_HELD) 상태가 아닌 건에 강제 환불 시도시 400 예외가 발생한다")
    void forceRefund_notEscrowHeld_throwsBadRequest() {
        // given
        application.setPaymentStatus(PaymentStatus.SETTLED);
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(mentoringApplicationRepository.findByIdForUpdate(applicationId)).willReturn(Optional.of(application));

        // when & then
        assertThatThrownBy(() -> adminMentoringService.forceRefund(adminId, applicationId, "사유"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("관리자 강제 정산 시 멘토에게 마일리지가 지급되고 menteeCount가 1 증가한다")
    void forceSettle_success() {
        // given
        int originalMenteeCount = mentorProfile.getMenteeCount();
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(mentoringApplicationRepository.findByIdForUpdate(applicationId)).willReturn(Optional.of(application));

        // when
        MentoringApplicationResponse response = adminMentoringService.forceSettle(adminId, applicationId, "세션 정상 진행 확인");

        // then
        assertThat(response).isNotNull();
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.COMPLETED);
        assertThat(application.getPaymentStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(mentorProfile.getMenteeCount()).isEqualTo(originalMenteeCount + 1);

        // 멘토에게 정산 마일리지 지급 호출 검증
        verify(mileageService, times(1)).addMileage(
                eq(mentorUser),
                eq(30000L),
                eq(TransactionType.SETTLEMENT),
                any(String.class),
                eq(applicationId)
        );

        verify(adminAuditLogRepository, times(1)).save(any(AdminAuditLog.class));
    }
}