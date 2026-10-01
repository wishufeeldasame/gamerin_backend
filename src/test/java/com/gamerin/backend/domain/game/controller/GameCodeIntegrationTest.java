package com.gamerin.backend.domain.game.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GameCodeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private UserRepository userRepository;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        User user = User.createLocal("game_" + UUID.randomUUID() + "@example.com",
                "g" + UUID.randomUUID().toString().replace("-", "").substring(0, 10), "game", "hash");
        user.setProfile(UserProfile.createDefault(user));
        user = userRepository.saveAndFlush(user);
        token = "Bearer " + jwtTokenProvider.createAccessToken(user.getId(), user.getHandle(), List.of("ROLE_USER"));

        mockMvc.perform(post("/api/v1/mentoring/mentors")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"about\":\"멘토\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void gamesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/games")).andExpect(status().isUnauthorized());
    }

    @Test
    void gamesReturnsSevenCodesInFixedOrder() throws Exception {
        mockMvc.perform(get("/api/v1/games").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(7)))
                .andExpect(jsonPath("$.data[0].code").value("PUBG"))
                .andExpect(jsonPath("$.data[0].name").value("PUBG"))
                .andExpect(jsonPath("$.data[1].code").value("R6"))
                .andExpect(jsonPath("$.data[1].name").value("Rainbow Six Siege"))
                .andExpect(jsonPath("$.data[2].code").value("LOL"))
                .andExpect(jsonPath("$.data[2].name").value("League of Legends"))
                .andExpect(jsonPath("$.data[6].code").value("OTHER"))
                .andExpect(jsonPath("$.data[6].name").value("기타"));
    }

    @Test
    void registerProgramRejectsNonCodeGameName() throws Exception {
        for (String game : new String[] {"League of Legends", "", "lol"}) {
            registerProgram(game).andExpect(status().isBadRequest());
        }
        registerProgram("League of Legends")
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("gameName")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PUBG, R6, LOL")));
        mockMvc.perform(post("/api/v1/mentoring/programs")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "t", "content", "c", "price", 0))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerProgramAcceptsCodeAndListFiltersByCode() throws Exception {
        registerProgram("LOL").andExpect(status().isOk());
        registerProgram("PUBG").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/mentoring/programs").param("gameName", "LOL")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].gameName").value("LOL"));
        mockMvc.perform(get("/api/v1/mentoring/programs").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.data.content", hasSize(2)));
        mockMvc.perform(get("/api/v1/mentoring/programs").param("gameName", "League of Legends")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PUBG, R6, LOL")));
    }

    @Test
    void updateProgramKeepsStoredGameCode() throws Exception {
        String id = objectMapper.readTree(registerProgram("LOL").andReturn().getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/mentoring/programs/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "새 제목", "content", "c2", "price", 2000, "status", "ACTIVE"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/mentoring/programs/{id}", id).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.data.title").value("새 제목"))
                .andExpect(jsonPath("$.data.gameName").value("LOL"));
    }

    private org.springframework.test.web.servlet.ResultActions registerProgram(String game) throws Exception {
        return mockMvc.perform(post("/api/v1/mentoring/programs")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("gameName", game, "title", "t", "content", "c", "price", 1000))));
    }
}
