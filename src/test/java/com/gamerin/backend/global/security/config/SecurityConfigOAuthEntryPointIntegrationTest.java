package com.gamerin.backend.global.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigOAuthEntryPointIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void protectedHtmlRequestDoesNotStartOAuthLoginAutomatically() throws Exception {
        mockMvc.perform(get("/protected-browser-page").accept(MediaType.TEXT_HTML))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void protectedApiRequestReturnsJsonUnauthorizedWithoutOAuthRedirect() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("인증이 필요하거나 토큰이 만료되었습니다."));
    }

    @Test
    void googleAuthorizationEndpointStillStartsOAuthLogin() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("accounts.google.com")));
    }
}
