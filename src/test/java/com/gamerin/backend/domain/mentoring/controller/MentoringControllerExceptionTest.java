package com.gamerin.backend.domain.mentoring.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringApplicationRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringProgramUpdateRequest;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringReviewRepository;
import com.gamerin.backend.domain.mentoring.service.MentoringService;
import com.gamerin.backend.domain.mentoring.service.SettlementProcessor;
import com.gamerin.backend.domain.notification.service.NotificationCommandService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.exception.InsufficientMileageException;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;
import com.gamerin.backend.global.exception.GlobalExceptionHandler;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MentoringService를 mock하지 않고 실제 서비스 객체와 GlobalExceptionHandler를 연동하여,
 * 서비스 레이어의 실제 비즈니스 검증(404, 403, 409), 하위 도메인 예외 전파(400),
 * 그리고 비인가 런타임 예외 마스킹(500)을 검증하는 MVC 슬라이스 테스트입니다.
 */
@ExtendWith(MockitoExtension.class)
class MentoringControllerExceptionTest {

    private MockMvc mockMvc;

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

    // 실제 MentoringService 객체를 생성하여 주입
    private MentoringService mentoringService;

    private ObjectMapper objectMapper = new ObjectMapper();
    private CustomUserPrincipal testPrincipal;
    private User testUser;
    private UUID testUserId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        testUser = User.createLocal("user@example.com", "user", "User", "Password123!");
        ReflectionTestUtils.setField(testUser, "id", testUserId);
        testPrincipal = CustomUserPrincipal.from(testUser);

        mentoringService = new MentoringService(
                mentorProfileRepository,
                userRepository,
                mentoringProgramRepository,
                mentoringApplicationRepository,
                mentoringReviewRepository,
                mileageService,
                settlementProcessor,
                notificationCommandService
        );

        MentoringController controller = new MentoringController(mentoringService);

        // @AuthenticationPrincipal 리졸버 등록
        HandlerMethodArgumentResolver authPrincipalResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
            }

            @Override
            public Object resolveArgument(
                    MethodParameter parameter,
                    ModelAndViewContainer mavContainer,
                    NativeWebRequest webRequest,
                    WebDataBinderFactory binderFactory
            ) {
                return testPrincipal;
            }
        };

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(authPrincipalResolver)
                .build();
    }

    @Test
    @DisplayName("없는 프로그램 수정 요청 시 실제 서비스에서 404 NOT_FOUND를 던지고 JSON 에러 메시지를 반환한다")
    void updateProgramNotFoundReturns404() throws Exception {
        UUID programId = UUID.randomUUID();
        MentoringProgramUpdateRequest request = new MentoringProgramUpdateRequest(
                "제목", "내용", "시간", 1000L, ProgramStatus.ACTIVE, List.of("tag")
        );

        // Repository 빈 응답 시 MentoringService 내부에서 404 throw 발생
        when(mentoringProgramRepository.findById(programId)).thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/v1/mentoring/programs/{id}", programId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("프로그램을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("타인의 프로그램 수정 요청 시 실제 서비스에서 403 FORBIDDEN을 던지고 JSON 에러 메시지를 반환한다")
    void updateProgramForbiddenReturns403() throws Exception {
        UUID programId = UUID.randomUUID();
        UUID otherMentorId = UUID.randomUUID();

        User otherUser = User.createLocal("other@example.com", "other", "Other", "Password123!");
        ReflectionTestUtils.setField(otherUser, "id", otherMentorId);

        MentorProfile otherMentor = new MentorProfile();
        otherMentor.setUser(otherUser);

        MentoringProgram program = new MentoringProgram();
        program.setId(programId);
        program.setMentor(otherMentor);

        // 타인 소유 프로그램 반환 시 MentoringService 권한 검증에서 403 throw 발생
        when(mentoringProgramRepository.findById(programId)).thenReturn(Optional.of(program));

        MentoringProgramUpdateRequest request = new MentoringProgramUpdateRequest(
                "제목", "내용", "시간", 1000L, ProgramStatus.ACTIVE, List.of("tag")
        );

        mockMvc.perform(patch("/api/v1/mentoring/programs/{id}", programId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("해당 프로그램을 수정할 권한이 없습니다."));
    }

    @Test
    @DisplayName("마감된 프로그램 신청 시 실제 서비스에서 409 CONFLICT를 던지고 JSON 에러 메시지를 반환한다")
    void applyToProgramConflictReturns409() throws Exception {
        UUID programId = UUID.randomUUID();
        MentoringApplicationRequest request = new MentoringApplicationRequest(programId, "신청합니다");

        User mentorUser = User.createLocal("mentor@example.com", "mentor", "Mentor", "Password123!");
        ReflectionTestUtils.setField(mentorUser, "id", UUID.randomUUID());

        MentorProfile mentorProfile = new MentorProfile();
        mentorProfile.setUser(mentorUser);

        MentoringProgram program = new MentoringProgram();
        program.setId(programId);
        program.setMentor(mentorProfile);
        program.setStatus(ProgramStatus.CLOSED);

        // 마감 상태 프로그램 반환 시 MentoringService 상태 검증에서 409 throw 발생
        when(mentoringProgramRepository.findByIdForUpdate(programId)).thenReturn(Optional.of(program));

        mockMvc.perform(post("/api/v1/mentoring/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("마감된 프로그램에는 신청할 수 없습니다."));
    }

    @Test
    @DisplayName("마일리지 사용 중 InsufficientMileageException 발생 시 400 BAD_REQUEST로 전파되어 응답된다")
    void applyToProgramInsufficientBalancePropagates400() throws Exception {
        UUID programId = UUID.randomUUID();
        MentoringApplicationRequest request = new MentoringApplicationRequest(programId, "신청합니다");

        User mentorUser = User.createLocal("mentor@example.com", "mentor", "Mentor", "Password123!");
        ReflectionTestUtils.setField(mentorUser, "id", UUID.randomUUID());

        MentorProfile mentorProfile = new MentorProfile();
        mentorProfile.setUser(mentorUser);

        MentoringProgram program = new MentoringProgram();
        program.setId(programId);
        program.setMentor(mentorProfile);
        program.setStatus(ProgramStatus.ACTIVE);
        program.setPrice(1000L);
        program.setTitle("PUBG 코칭");

        when(mentoringProgramRepository.findByIdForUpdate(programId)).thenReturn(Optional.of(program));
        when(mentoringApplicationRepository.existsByMenteeIdAndProgramIdAndStatusIn(any(), any(), any()))
                .thenReturn(false);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));

        // 하위 MileageService에서 도메인 예외가 올라오는 상황을 모킹하여 상위 계층 전파 검증
        doThrow(new InsufficientMileageException(0L))
                .when(mileageService).useMileage(any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/v1/mentoring/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("마일리지가 부족합니다. (현재 잔액: 0)"));
    }

    @Test
    @DisplayName("예상하지 못한 일반 RuntimeException 발생 시 500 INTERNAL_SERVER_ERROR로 마스킹되어 반환된다")
    void unhandledRuntimeExceptionReturns500() throws Exception {
        UUID programId = UUID.randomUUID();
        MentoringApplicationRequest request = new MentoringApplicationRequest(programId, "신청합니다");

        // DB 타임아웃 등 예상치 못한 런타임 예외 상황
        when(mentoringProgramRepository.findByIdForUpdate(programId))
                .thenThrow(new RuntimeException("DB Connection Timeout"));

        mockMvc.perform(post("/api/v1/mentoring/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("서버 처리 중 오류가 발생했습니다."));
    }
}