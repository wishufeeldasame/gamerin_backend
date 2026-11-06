package com.gamerin.backend.domain.user.integration;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
class UserSettingsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private UserRepository userRepository;

    @Test
    void themeIsNullUntilUserChoosesOne() throws Exception {
        String token = bearerToken(saveUser());

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.theme").value(nullValue()));
    }

    @Test
    void settingsUpdatePersistsThemeAndMeReturnsIt() throws Exception {
        String token = bearerToken(saveUser());

        mockMvc.perform(patch("/api/v1/users/me/settings")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("theme", "LIGHT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(nullValue()));

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.theme").value("LIGHT"));
    }

    @Test
    void invalidThemeIsBadRequestAndDoesNotChangeStoredTheme() throws Exception {
        String token = bearerToken(saveUser());
        mockMvc.perform(patch("/api/v1/users/me/settings")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("theme", "LIGHT"))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/users/me/settings")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("theme", "SYSTEM"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.theme").value("LIGHT"));
    }

    @Test
    void nullThemeIsBadRequest() throws Exception {
        String token = bearerToken(saveUser());

        mockMvc.perform(patch("/api/v1/users/me/settings")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    private User saveUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User user = User.createLocal(
                "theme+" + suffix + "@example.com",
                "theme" + suffix,
                "Theme Tester",
                "encoded-password"
        );
        user.setProfile(UserProfile.createDefault(user));
        return userRepository.saveAndFlush(user);
    }

    private String bearerToken(User user) {
        return "Bearer " + jwtTokenProvider.createAccessToken(user.getId(), user.getHandle(), List.of("ROLE_USER"));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
