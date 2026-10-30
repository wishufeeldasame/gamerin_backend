package com.gamerin.backend.domain.admin.service;

import com.gamerin.backend.domain.admin.dto.response.AdminUserResponse;
import com.gamerin.backend.domain.report.entity.PenaltyType;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.entity.UserPenalty;
import com.gamerin.backend.domain.report.repository.ReportRepository;
import com.gamerin.backend.domain.report.repository.UserPenaltyRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserRole;
import com.gamerin.backend.domain.user.entity.UserStatus;
import com.gamerin.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private UserPenaltyRepository userPenaltyRepository;

    @InjectMocks
    private AdminUserService adminUserService;

    private User user1;
    private User user2;
    private UUID user1Id;
    private UUID user2Id;

    @BeforeEach
    void setUp() {
        user1Id = UUID.randomUUID();
        user2Id = UUID.randomUUID();

        user1 = User.createLocal("user1@test.com", "user01", "유저원", "encodedPw");
        ReflectionTestUtils.setField(user1, "id", user1Id);

        user2 = User.createLocal("user2@test.com", "user02", "유저투", "encodedPw");
        ReflectionTestUtils.setField(user2, "id", user2Id);
    }

    @Test
    @DisplayName("어드민 유저 목록 조회 시 신고 수와 활성 제재 정보가 정상 결합된다")
    void getAdminUsers_success() {
        // given
        Pageable pageable = PageRequest.of(0, 10);
        Page<User> userPage = new PageImpl<>(List.of(user1, user2), pageable, 2);

        given(userRepository.searchUsersForAdmin("user", UserStatus.ACTIVE, null, pageable))
            .willReturn(userPage);

        // user1은 신고 3회, 제재 없음
        given(reportRepository.countByTargetTypeAndTargetId(ReportTargetType.USER, user1Id))
                .willReturn(3L);
        given(userPenaltyRepository.findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(user1Id))
                .willReturn(List.of());

        // user2는 신고 5회, 7일 정지 제재 있음
        given(reportRepository.countByTargetTypeAndTargetId(ReportTargetType.USER, user2Id))
                .willReturn(5L);
        UserPenalty activePenalty = UserPenalty.create(
                user2, null, PenaltyType.SUSPENSION_7D, "욕설", OffsetDateTime.now().plusDays(7), null);
        ReflectionTestUtils.setField(activePenalty, "id", UUID.randomUUID());
        given(userPenaltyRepository.findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(user2Id))
                .willReturn(List.of(activePenalty));

        // when
        Page<AdminUserResponse> result = adminUserService.getAdminUsers("user", UserStatus.ACTIVE, null, pageable);

        // then
        assertThat(result.getContent()).hasSize(2);

        AdminUserResponse res1 = result.getContent().get(0);
        assertThat(res1.handle()).isEqualTo("user01");
        assertThat(res1.reportsReceivedCount()).isEqualTo(3L);
        assertThat(res1.activeSanction()).isEqualTo("없음");

        AdminUserResponse res2 = result.getContent().get(1);
        assertThat(res2.handle()).isEqualTo("user02");
        assertThat(res2.reportsReceivedCount()).isEqualTo(5L);
        assertThat(res2.activeSanction()).isEqualTo("7일 정지");
    }

    @Test
    @DisplayName("핸들(@handle)로 상세 조회 시 @ 접두사가 있어도 정상 조회된다")
    void getAdminUserByHandle_success() {
        // given
        given(userRepository.findByHandle("user01")).willReturn(Optional.of(user1));
        given(reportRepository.countByTargetTypeAndTargetId(ReportTargetType.USER, user1Id)).willReturn(2L);
        given(userPenaltyRepository.findByUserIdAndIsActiveTrueOrderByCreatedAtDesc(user1Id)).willReturn(List.of());

        // when
        AdminUserResponse response = adminUserService.getAdminUserByHandle("@user01");

        // then
        assertThat(response).isNotNull();
        assertThat(response.handle()).isEqualTo("user01");
        assertThat(response.nickname()).isEqualTo("유저원");
        assertThat(response.reportsReceivedCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("존재하지 않는 핸들 조회 시 404 예외가 발생한다")
    void getAdminUserByHandle_notFound() {
        // given
        given(userRepository.findByHandle("unknown")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminUserService.getAdminUserByHandle("unknown"))
                .isInstanceOf(ResponseStatusException.class);
    }
}