package com.gamerin.backend.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.global.logging.JsonLogContext;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(exceptionHandler)
                .build();
    }

    @Test
    void handleRuntimeExceptionReturnsGenericInternalServerError() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RuntimeException exception = new RuntimeException("database password leaked");

        ResponseEntity<Map<String, Object>> response =
                exceptionHandler.handleRuntimeException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("message", "서버 처리 중 오류가 발생했습니다.");
        assertThat(response.getBody()).containsEntry("data", null);
        assertThat(response.getBody()).doesNotContainEntry("message", "database password leaked");
        assertThat(JsonLogContext.getFailureReason(request)).isEqualTo("서버 처리 중 오류가 발생했습니다.");
    }

    @Test
    void runtimeExceptionWithNullMessageStillReturnsGenericInternalServerError() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<Map<String, Object>> response =
                exceptionHandler.handleRuntimeException(new RuntimeException(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("message", "서버 처리 중 오류가 발생했습니다.");
        assertThat(response.getBody()).containsEntry("data", null);
        assertThat(JsonLogContext.getFailureReason(request)).isEqualTo("서버 처리 중 오류가 발생했습니다.");
    }

    @Test
    void runtimeExceptionWithSensitiveCauseDoesNotExposeAnyInternalMessage() throws Exception {
        mockMvc.perform(get("/test/runtime-with-cause"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("서버 처리 중 오류가 발생했습니다."))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(content().string(not(containsString("outer database error"))))
                .andExpect(content().string(not(containsString("jdbc:postgresql://internal-db"))));
    }

    @Test
    void runtimeExceptionSubclassReturnsGenericInternalServerError() throws Exception {
        mockMvc.perform(get("/test/illegal-state"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("서버 처리 중 오류가 발생했습니다."))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(content().string(not(containsString("state contained secret token"))));
    }

    @Test
    void nullPointerExceptionReturnsGenericInternalServerError() throws Exception {
        mockMvc.perform(get("/test/null-pointer"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("서버 처리 중 오류가 발생했습니다."))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(content().string(not(containsString("user.passwordHash"))));
    }

    @Test
    void illegalArgumentExceptionStillUsesBadRequestHandler() throws Exception {
        mockMvc.perform(get("/test/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void responseStatusExceptionStillUsesDeclaredStatusHandler() throws Exception {
        mockMvc.perform(get("/test/response-status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("대상을 찾을 수 없습니다."))
                .andExpect(content().string(not(containsString("서버 처리 중 오류가 발생했습니다."))));
    }

    @RestController
    private static class ThrowingController {

        @GetMapping("/test/runtime-with-cause")
        void runtimeWithCause() {
            throw new RuntimeException(
                    "outer database error",
                    new IllegalStateException("jdbc:postgresql://internal-db")
            );
        }

        @GetMapping("/test/illegal-state")
        void illegalState() {
            throw new IllegalStateException("state contained secret token");
        }

        @GetMapping("/test/null-pointer")
        void nullPointer() {
            throw new NullPointerException("user.passwordHash");
        }

        @GetMapping("/test/illegal-argument")
        void illegalArgument() {
            throw new IllegalArgumentException("잘못된 요청입니다.");
        }

        @GetMapping("/test/response-status")
        void responseStatus() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다.");
        }
    }
}
