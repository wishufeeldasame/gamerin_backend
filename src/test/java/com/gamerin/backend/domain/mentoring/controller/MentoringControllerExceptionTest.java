package com.gamerin.backend.domain.mentoring.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringApplicationRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringProgramUpdateRequest;
import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;
import com.gamerin.backend.domain.mentoring.service.MentoringService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.global.exception.GlobalExceptionHandler;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*  완료 조건인 **"서비스 예외만이 아니라 HTTP 응답 상태와 메시지까지 검증하는
    테스트"**를 충족하기 위해, MockMvc와 GlobalExceptionHandler를 연동하여 실제 HTTP 400,
    403, 404, 409 응답 및 { "success": false, "message": "..." } 포맷을 검증하는
    테스트입니다.
*/
@ExtendWith(MockitoExtension.class)
class MentoringControllerExceptionTest {

    private MockMvc mockMvc;

    @Mock
    private MentoringService mentoringService;

    private ObjectMapper objectMapper = new ObjectMapper();
    private CustomUserPrincipal testPrincipal;

    @BeforeEach
    void setUp() {
        User user = User.createLocal("user@example.com", "user", "User", "Password123!");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        testPrincipal = CustomUserPrincipal.from(user);

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
    @DisplayName("없는 프로그램 수정 요청 시 404 NOT_FOUND와 에러 메시지를 반환한다")
    void updateProgramNotFoundReturns404() throws Exception {
        UUID programId = UUID.randomUUID();
        MentoringProgramUpdateRequest request = new MentoringProgramUpdateRequest(
                "제목", "내용", "시간", 1000L, ProgramStatus.ACTIVE, List.of("tag")
        );

        when(mentoringService.updateProgram(any(), eq(programId), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "프로그램을 찾을 수 없습니다."));

        mockMvc.perform(patch("/api/v1/mentoring/programs/{id}", programId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("프로그램을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("타인의 프로그램 수정 요청 시 403 FORBIDDEN을 반환한다")
    void updateProgramForbiddenReturns403() throws Exception {
        UUID programId = UUID.randomUUID();
        MentoringProgramUpdateRequest request = new MentoringProgramUpdateRequest(
                "제목", "내용", "시간", 1000L, ProgramStatus.ACTIVE, List.of("tag")
        );

        when(mentoringService.updateProgram(any(), eq(programId), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 프로그램을 수정할 권한이 없습니다."));

        mockMvc.perform(patch("/api/v1/mentoring/programs/{id}", programId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("해당 프로그램을 수정할 권한이 없습니다."));
    }

    @Test
    @DisplayName("마감된 프로그램 신청 또는 중복 신청 시 409 CONFLICT를 반환한다")
    void applyToProgramConflictReturns409() throws Exception {
        MentoringApplicationRequest request = new MentoringApplicationRequest(UUID.randomUUID(), "신청합니다");

        when(mentoringService.applyToProgram(any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "마감된 프로그램에는 신청할 수 없습니다."));

        mockMvc.perform(post("/api/v1/mentoring/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("마감된 프로그램에는 신청할 수 없습니다."));
    }

    @Test
    @DisplayName("마일리지 잔액 부족 시 400 BAD_REQUEST를 반환한다")
    void applyToProgramInsufficientBalanceReturns400() throws Exception {
        MentoringApplicationRequest request = new MentoringApplicationRequest(UUID.randomUUID(), "신청합니다");

        when(mentoringService.applyToProgram(any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "마일리지가 부족합니다. (현재 잔액: 0)"));

        mockMvc.perform(post("/api/v1/mentoring/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("마일리지가 부족합니다. (현재 잔액: 0)"));
    }

    @Test
    @DisplayName("예상하지 못한 일반 RuntimeException은 기존처럼 500을 반환한다")
    void unhandledRuntimeExceptionReturns500() throws Exception {
        MentoringApplicationRequest request = new MentoringApplicationRequest(UUID.randomUUID(), "신청합니다");

        when(mentoringService.applyToProgram(any(), any()))
                .thenThrow(new RuntimeException("DB Connection Timeout"));

        mockMvc.perform(post("/api/v1/mentoring/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("서버 처리 중 오류가 발생했습니다."));
    }
}