package com.gamerin.backend.domain.report.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.report.dto.request.UserPenaltyCreateRequest;
import com.gamerin.backend.domain.report.entity.PenaltyType;
import com.gamerin.backend.domain.report.repository.UserPenaltyRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.entity.UserRole;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 어드민 제재 부여, 정지 유저 API 403 접근 차단, 제재 해제 E2E 통합 테스트
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class AdminUserPenaltyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserPenaltyRepository userPenaltyRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private User admin;
    private User regularUser;
    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        // 어드민 유저 생성 (프로필 기본값 포함)
        admin = User.createLocal("admin@test.com", "admin_" + UUID.randomUUID().toString().substring(0, 8), "어드민", "hash");
        admin.setProfile(UserProfile.createDefault(admin));
        ReflectionTestUtils.setField(admin, "role", UserRole.ADMIN);
        admin = userRepository.save(admin);
        adminToken = jwtTokenProvider.createAccessToken(admin.getId(), admin.getHandle(), List.of("ADMIN"));

        // 일반 유저 생성 (프로필 기본값 포함)
        regularUser = User.createLocal("user@test.com", "user_" + UUID.randomUUID().toString().substring(0, 8), "일반유저", "hash");
        regularUser.setProfile(UserProfile.createDefault(regularUser));
        regularUser = userRepository.save(regularUser);
        userToken = jwtTokenProvider.createAccessToken(regularUser.getId(), regularUser.getHandle(), List.of("USER"));
    }

    @Test
    @DisplayName("[통합 검증] 제재 부여 -> 유저 API 호출 시 403 차단 -> 제재 해제 -> 정상 접근")
    void fullPenaltyLifecycleTest() throws Exception {
        // 1. 제재 전 일반 유저 정상 접근 확인 (200 OK)
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        // 2. 어드민이 해당 유저에게 7일 정지 제재 부여 (POST /api/v1/admin/users/{userId}/penalties)
        UserPenaltyCreateRequest createRequest = new UserPenaltyCreateRequest(
                PenaltyType.SUSPENSION_7D, "불량 게시글 반복 작성", 7, null
        );

        String responseJson = mockMvc.perform(post("/api/v1/admin/users/{userId}/penalties", regularUser.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.penaltyType").value("SUSPENSION_7D"))
                .andReturn().getResponse().getContentAsString();

        String penaltyIdStr = objectMapper.readTree(responseJson).get("data").get("id").asText();
        UUID penaltyId = UUID.fromString(penaltyIdStr);

        // 3. 정지된 유저가 기존 토큰으로 인증 API 호출 시 미들웨어(UserSuspensionFilter)에 의해 403 Forbidden 차단 확인
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("이용이 정지된 계정입니다. 고객센터에 문의해주세요."));

        // 4. 어드민이 제재 수동 조기 해제 (DELETE /api/v1/admin/users/{userId}/penalties/{penaltyId})
        mockMvc.perform(delete("/api/v1/admin/users/{userId}/penalties/{penaltyId}", regularUser.getId(), penaltyId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 5. 제재 해제 후 일반 유저가 다시 정상 접근 가능한지 확인 (200 OK)
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(regularUser.getId().toString()));
    }
}