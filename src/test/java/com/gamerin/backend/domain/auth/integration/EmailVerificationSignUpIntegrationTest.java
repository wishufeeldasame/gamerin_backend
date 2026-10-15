package com.gamerin.backend.domain.auth.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.auth.repository.EmailVerificationRepository;
import com.gamerin.backend.domain.auth.service.EmailVerificationMailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 틀린 시도 횟수가 실제로 커밋되는지 보려고 테스트 트랜잭션으로 감싸지 않는다
@SpringBootTest
@AutoConfigureMockMvc
class EmailVerificationSignUpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmailVerificationRepository emailVerificationRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private EmailVerificationMailService emailVerificationMailService;

    @Test
    void signUpSucceedsOnlyAfterEmailVerification() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String handle = "verify" + suffix;
        String email = "verify+" + suffix + "@example.com";

        mockMvc.perform(post("/api/v1/auth/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300))
                .andExpect(jsonPath("$.data.resendCooldownSeconds").value(60));

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailVerificationMailService).sendSignupVerificationCode(eq(email), codeCaptor.capture());
        String code = codeCaptor.getValue();
        String wrongCode = code.equals("000000") ? "111111" : "000000";

        mockMvc.perform(post("/api/v1/auth/email-verifications/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "code", wrongCode))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("인증번호가 일치하지 않습니다. (남은 횟수: 4회)"));

        // 잠금 조회라 트랜잭션 안에서 읽는다
        Integer attemptCount = new TransactionTemplate(transactionManager).execute(tx ->
                emailVerificationRepository.findTopByEmailOrderByCreatedAtDesc(email).orElseThrow().getAttemptCount());
        assertThat(attemptCount).isEqualTo(1);

        MvcResult confirmResult = mockMvc.perform(post("/api/v1/auth/email-verifications/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "code", code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(1800))
                .andReturn();
        String verificationToken = objectMapper.readTree(confirmResult.getResponse().getContentAsString())
                .path("data").path("verificationToken").asText();

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(signUpBody(handle, email, verificationToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handle").value(handle));

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(signUpBody(handle + "2", "other+" + suffix + "@example.com", verificationToken))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이메일 인증이 만료되었거나 유효하지 않습니다. 이메일 인증을 다시 진행해주세요."));
    }

    @Test
    void signUpRejectsUnverifiedEmailToken() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(signUpBody("noverify" + suffix, "noverify+" + suffix + "@example.com", "forged-token"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    private Map<String, Object> signUpBody(String handle, String email, String emailVerificationToken) {
        return Map.of(
                "handle", handle,
                "nickname", "Verify Demo",
                "email", email,
                "password", "Password1!",
                "passwordConfirm", "Password1!",
                "agreedToTerms", true,
                "agreedToPrivacy", true,
                "emailVerificationToken", emailVerificationToken
        );
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
