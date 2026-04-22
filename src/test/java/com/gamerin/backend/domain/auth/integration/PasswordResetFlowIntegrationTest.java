package com.gamerin.backend.domain.auth.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.auth.service.PasswordResetMailService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PasswordResetFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PasswordResetMailService passwordResetMailService;

    @Test
    void passwordResetFlowInvalidatesOldCredentialsAndAllowsNewPassword() throws Exception {
        String handle = "reset" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String email = "reset+" + UUID.randomUUID() + "@example.com";
        String oldPassword = "Password1!";
        String newPassword = "NewPassword1!";

        MvcResult signUpResult = mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "handle", handle,
                                "nickname", "Reset Demo",
                                "email", email,
                                "password", oldPassword,
                                "passwordConfirm", oldPassword,
                                "agreedToTerms", true,
                                "agreedToPrivacy", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.handle").value(handle))
                .andReturn();

        Cookie initialRefreshCookie = signUpResult.getResponse().getCookie("refresh_token");
        assertThat(initialRefreshCookie).isNotNull();

        mockMvc.perform(post("/api/v1/auth/find-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("handle", handle))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isEmpty());

        ArgumentCaptor<String> resetTokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordResetMailService).sendPasswordResetMail(eq(email), resetTokenCaptor.capture());
        String resetToken = resetTokenCaptor.getValue();
        assertThat(resetToken).isNotBlank();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "resetToken", resetToken,
                                "newPassword", newPassword,
                                "newPasswordConfirm", newPassword
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "handle", handle,
                                "password", oldPassword
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "handle", handle,
                                "password", newPassword
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.handle").value(handle));

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "resetToken", resetToken,
                                "newPassword", "AnotherPass1!",
                                "newPasswordConfirm", "AnotherPass1!"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("유효하지 않은 비밀번호 재설정 토큰입니다."));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(initialRefreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void findPasswordReturnsSuccessEvenWhenHandleDoesNotExist() throws Exception {
        mockMvc.perform(post("/api/v1/auth/find-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("handle", "missing-user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isEmpty());

        verifyNoInteractions(passwordResetMailService);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
