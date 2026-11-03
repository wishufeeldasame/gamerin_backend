package com.gamerin.backend.domain.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.auth.entity.ConsentType;
import com.gamerin.backend.domain.auth.entity.SocialSignupSession;
import com.gamerin.backend.domain.auth.entity.UserConsent;
import com.gamerin.backend.domain.auth.repository.EmailVerificationRepository;
import com.gamerin.backend.domain.auth.repository.SocialSignupSessionRepository;
import com.gamerin.backend.domain.auth.repository.UserConsentRepository;
import com.gamerin.backend.domain.auth.service.EmailVerificationMailService;
import com.gamerin.backend.domain.auth.service.TokenService;
import com.gamerin.backend.domain.user.repository.UserRepository;

// 가입 트랜잭션의 커밋/롤백을 보려고 테스트 트랜잭션으로 감싸지 않는다
@SpringBootTest
@AutoConfigureMockMvc
class SignupConsentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EmailVerificationRepository emailVerificationRepository;
    @Autowired
    private SocialSignupSessionRepository socialSignupSessionRepository;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private UserConsentRepository userConsentRepository;
    @MockitoBean
    private EmailVerificationMailService emailVerificationMailService;

    @AfterEach
    void resetSpy() {
        reset(userConsentRepository);
    }

    @Test
    void localSignUpRecordsThreeConsentsWithSameTimestamp() throws Exception {
        String suffix = suffix();
        String token = verifyEmail("consent+" + suffix + "@example.com");

        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(localBody(suffix, token, Map.of()))))
                .andExpect(status().isOk());

        assertConsentsRecorded(userRepository.findByHandle("consent" + suffix).orElseThrow().getId());
    }

    @Test
    void socialSignUpRecordsThreeConsentsWithSameTimestamp() throws Exception {
        String suffix = suffix();
        String signupToken = "social-token-" + suffix;
        socialSignupSessionRepository.save(SocialSignupSession.create("google", "gid-" + suffix,
                "social+" + suffix + "@example.com", "Social", tokenService.sha256(signupToken), 30));

        mockMvc.perform(post("/api/v1/auth/social-signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(socialBody(suffix, signupToken, Map.of()))))
                .andExpect(status().isOk());

        assertConsentsRecorded(userRepository.findByHandle("social" + suffix).orElseThrow().getId());
    }

    @Test
    void localSignUpRejectsInvalidConsentFields() throws Exception {
        String suffix = suffix();
        for (Map<String, Object> override : invalidConsentOverrides()) {
            mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                            .content(json(localBody(suffix, "any-token", override))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
        assertThat(userRepository.findByHandle("consent" + suffix)).isEmpty();
    }

    @Test
    void socialSignUpRejectsInvalidConsentFields() throws Exception {
        String suffix = suffix();
        for (Map<String, Object> override : invalidConsentOverrides()) {
            mockMvc.perform(post("/api/v1/auth/social-signup").contentType(MediaType.APPLICATION_JSON)
                            .content(json(socialBody(suffix, "any-token", override))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
        assertThat(userRepository.findByHandle("social" + suffix)).isEmpty();
    }

    @Test
    void localVersionMismatchDoesNotConsumeEmailTokenOrCreateUser() throws Exception {
        String suffix = suffix();
        String email = "consent+" + suffix + "@example.com";
        String token = verifyEmail(email);

        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(localBody(suffix, token, Map.of("termsVersion", "0.1")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("약관이 개정되었습니다. 페이지를 새로고침한 뒤 다시 동의해 주세요."));

        assertThat(userRepository.findByHandle("consent" + suffix)).isEmpty();
        // 토큰이 소비되지 않았으므로 올바른 버전으로 같은 토큰을 다시 쓸 수 있다
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(localBody(suffix, token, Map.of()))))
                .andExpect(status().isOk());
    }

    @Test
    void socialVersionMismatchKeepsSessionAndCreatesNoUser() throws Exception {
        String suffix = suffix();
        String signupToken = "social-token-" + suffix;
        socialSignupSessionRepository.save(SocialSignupSession.create("google", "gid-" + suffix,
                "social+" + suffix + "@example.com", "Social", tokenService.sha256(signupToken), 30));

        mockMvc.perform(post("/api/v1/auth/social-signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(socialBody(suffix, signupToken, Map.of("privacyVersion", "0.1")))))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findByHandle("social" + suffix)).isEmpty();
        assertThat(socialSignupSessionRepository.findBySignupTokenHash(tokenService.sha256(signupToken))).isPresent();
    }

    @Test
    void consentRecordFailureRollsBackUserAndKeepsEmailTokenUsable() throws Exception {
        String suffix = suffix();
        String token = verifyEmail("consent+" + suffix + "@example.com");
        doThrow(new IllegalStateException("boom")).when(userConsentRepository).saveAll(any());

        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(localBody(suffix, token, Map.of()))))
                .andExpect(status().isInternalServerError());

        assertThat(userRepository.findByHandle("consent" + suffix)).isEmpty();
        reset(userConsentRepository);
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(localBody(suffix, token, Map.of()))))
                .andExpect(status().isOk());
    }

    private void assertConsentsRecorded(UUID userId) {
        List<UserConsent> consents = userConsentRepository.findAllByUserId(userId);
        assertThat(consents).extracting(UserConsent::getConsentType)
                .containsExactlyInAnyOrder(ConsentType.TERMS, ConsentType.PRIVACY, ConsentType.AGE_14);
        assertThat(consents).extracting(UserConsent::getVersion).containsOnly("1.0");
        assertThat(consents).extracting(UserConsent::getAgreedAt).doesNotContainNull()
                .containsOnly(consents.get(0).getAgreedAt());
    }

    // 필드별 검증은 SignUpValidationTest가 다룬다. 여기서는 false, 필드 누락(null), 버전 누락만 대표로 확인한다.
    // 가입 API는 IP당 분당 10회로 제한되므로 호출 수를 줄인다.
    private List<Map<String, Object>> invalidConsentOverrides() {
        return List.of(Map.of("agreedToTerms", false), nullField("agreedToAge14"), nullField("privacyVersion"));
    }

    private Map<String, Object> nullField(String field) {
        Map<String, Object> map = new HashMap<>();
        map.put(field, null);
        return map;
    }

    private Map<String, Object> localBody(String suffix, String token, Map<String, Object> override) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "handle", "consent" + suffix,
                "nickname", "Consent",
                "email", "consent+" + suffix + "@example.com",
                "password", "Password1!",
                "passwordConfirm", "Password1!",
                "emailVerificationToken", token));
        body.putAll(consentFields());
        body.putAll(override);
        return body;
    }

    private Map<String, Object> socialBody(String suffix, String signupToken, Map<String, Object> override) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "signupToken", signupToken,
                "handle", "social" + suffix,
                "nickname", "Social"));
        body.putAll(consentFields());
        body.putAll(override);
        return body;
    }

    private Map<String, Object> consentFields() {
        return Map.of("agreedToTerms", true, "agreedToPrivacy", true, "agreedToAge14", true,
                "termsVersion", "1.0", "privacyVersion", "1.0");
    }

    private String verifyEmail(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/email-verifications").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email))))
                .andExpect(status().isOk());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailVerificationMailService).sendSignupVerificationCode(eq(email), code.capture());
        MvcResult result = mockMvc.perform(post("/api/v1/auth/email-verifications/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "code", code.getValue()))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("verificationToken").asText();
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
