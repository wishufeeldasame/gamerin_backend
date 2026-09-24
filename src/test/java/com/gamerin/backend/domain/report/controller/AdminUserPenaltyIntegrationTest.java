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

    @Autowired
    private com.gamerin.backend.domain.post.repository.PostRepository postRepository;

    @Autowired
        private com.gamerin.backend.domain.message.repository.MessageConversationRepository messageConversationRepository;
    
    @Autowired
    private com.gamerin.backend.domain.message.repository.MessageParticipantRepository messageParticipantRepository;
    
    @Autowired
    private com.gamerin.backend.domain.message.repository.DirectMessageRepository directMessageRepository;


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

    @Test
    @DisplayName("[중복 신고 검증] 동일 유저가 동일 대상을 중복 신고하면 409 Conflict가 반환된다")
    void createReport_duplicate_returnsConflict() throws Exception {
        // given: 신고 요청 DTO 준비 (신고 대상을 admin 계정으로 지정)
        com.gamerin.backend.domain.report.dto.request.ReportCreateRequest request =
                new com.gamerin.backend.domain.report.dto.request.ReportCreateRequest(
                        com.gamerin.backend.domain.report.entity.ReportTargetType.USER,
                        admin.getId(),
                        com.gamerin.backend.domain.report.entity.ReportReasonCode.INAPPROPRIATE,
                        "비매너 행위 신고"
                );

        String requestJson = objectMapper.writeValueAsString(request);

        // when 1: 첫 번째 신고 접수 -> 200 OK 성공
        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.targetId").value(admin.getId().toString()));

        // when 2: 동일 대상에 대해 중복 신고 요청 -> 409 CONFLICT 발생 검증
        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("이미 해당 콘텐츠/유저에 대해 신고를 접수하셨습니다")));
    }

    @Test
    @DisplayName("[신고 검증] 이미 작성자가 삭제한 게시글을 신고하려고 하면 404 Not Found로 차단된다")
    void reportAlreadyDeletedPost_throwsNotFound() throws Exception {
        // given: 작성자가 이미 직접 삭제(softDelete)한 게시글 준비
        com.gamerin.backend.domain.post.entity.Post post = postRepository.save(
                com.gamerin.backend.domain.post.entity.Post.create(regularUser, "작성자가 직접 삭제한 게시글")
        );
        post.softDelete();
        post = postRepository.save(post);

        com.gamerin.backend.domain.report.dto.request.ReportCreateRequest request =
                new com.gamerin.backend.domain.report.dto.request.ReportCreateRequest(
                        com.gamerin.backend.domain.report.entity.ReportTargetType.POST,
                        post.getId(),
                        com.gamerin.backend.domain.report.entity.ReportReasonCode.SPAM,
                        "이미 삭제된 글 신고 시도"
                );

        // when & then: 404 NOT_FOUND로 거절되어 자동 숨김 큐에 진입하지 못함을 검증
        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("존재하지 않거나 이미 삭제되었습니다.")));
    }

    @Test
    @DisplayName("[제재 유효성 검증] 존재하지 않는 reportId로 제재를 시도하면 404 Not Found로 차단된다")
    void createPenalty_invalidReportId_throwsNotFound() throws Exception {
        UUID nonExistentReportId = UUID.randomUUID();

        // given: 존재하지 않는 임의의 reportId를 포함한 제재 요청
        UserPenaltyCreateRequest request = new UserPenaltyCreateRequest(
                PenaltyType.WARNING, "허위 신고 연계 제재 시도", 0, nonExistentReportId
        );

        // when & then: 404 NOT_FOUND 및 정확한 에러 메시지 반환 검증
        mockMvc.perform(post("/api/v1/admin/users/{userId}/penalties", regularUser.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("연관된 신고 내역을 찾을 수 없습니다")));
    }

    @Test
    @DisplayName("[정지 유저 신고 검증] SUSPENDED 상태인 유저도 404 거절 없이 정상 신고된다")
    void reportSuspendedUser_success() throws Exception {
        // 1. 피신고 대상이 될 유저 생성 후 정지(SUSPENDED) 상태로 저장
        User suspendedUser = User.createLocal(
                "suspended@test.com",
                "suspended_" + UUID.randomUUID().toString().substring(0, 8),
                "정지유저",
                "hash"
        );
        suspendedUser.setProfile(UserProfile.createDefault(suspendedUser));
        suspendedUser.suspend();
        suspendedUser = userRepository.save(suspendedUser);

        // 2. 일반 유저(regularUser)가 정지된 유저(suspendedUser)를 신고하는 요청 생성
        var request = new com.gamerin.backend.domain.report.dto.request.ReportCreateRequest(
                com.gamerin.backend.domain.report.entity.ReportTargetType.USER,
                suspendedUser.getId(),
                com.gamerin.backend.domain.report.entity.ReportReasonCode.INAPPROPRIATE,
                "정지 중인 유저의 추가 비매너 행위 신고"
        );

        // 3. 정지 유저 대상 신고 API 호출 시 404가 아니라 200 OK로 성공해야 함
        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.targetId").value(suspendedUser.getId().toString()));
    }

    @Test
    @DisplayName("[보안 검증] 대화 참여자가 아닌 제3자가 메시지를 신고하면 404 차단되고 사적 내용이 노출되지 않는다")
    void reportMessage_notParticipant_throwsNotFound() throws Exception {
        // 1. 대화 당사자(발신자, 수신자) 두 명 생성
        User sender = User.createLocal(
                "sender@test.com",
                "sender_" + UUID.randomUUID().toString().substring(0, 8),
                "발신자",
                "hash"
        );
        sender.setProfile(UserProfile.createDefault(sender));
        sender = userRepository.save(sender);

        User receiver = User.createLocal(
                "receiver@test.com",
                "receiver_" + UUID.randomUUID().toString().substring(0, 8),
                "수신자",
                "hash"
        );
        receiver.setProfile(UserProfile.createDefault(receiver));
        receiver = userRepository.save(receiver);

        // 2. 대화방(Conversation) 생성 및 두 명을 참여자로 등록
        var conversation = messageConversationRepository.save(
                com.gamerin.backend.domain.message.entity.MessageConversation.createDirect("direct_" + UUID.randomUUID())
        );
        messageParticipantRepository.save(com.gamerin.backend.domain.message.entity.MessageParticipant.create(conversation, sender));
        messageParticipantRepository.save(com.gamerin.backend.domain.message.entity.MessageParticipant.create(conversation, receiver));

        // 3. 비밀 메시지 생성
        var secretMessage = directMessageRepository.save(
                com.gamerin.backend.domain.message.entity.DirectMessage.create(
                        conversation,
                        sender,
                        "외부에 노출되면 안 되는 둘만의 비밀 대화입니다.",
                        null
                )
        );

        // 4. 대화 참여자가 아닌 제3자(regularUser)가 해당 messageId로 신고 API 호출
        var request = new com.gamerin.backend.domain.report.dto.request.ReportCreateRequest(
                com.gamerin.backend.domain.report.entity.ReportTargetType.MESSAGE,
                secretMessage.getId(),
                com.gamerin.backend.domain.report.entity.ReportReasonCode.INAPPROPRIATE,
                "남의 비밀 메시지 무단 신고 시도"
        );

        // 5. 참여자가 아니므로 404 Not Found ("접근 권한이 없습니다")로 차단됨을 검증
        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("접근 권한이 없습니다")));
    }
}