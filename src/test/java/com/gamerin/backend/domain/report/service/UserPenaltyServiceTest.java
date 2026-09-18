package com.gamerin.backend.domain.report.service;

import com.gamerin.backend.domain.admin.repository.AdminAuditLogRepository;
import com.gamerin.backend.domain.auth.repository.RefreshTokenRepository;
import com.gamerin.backend.domain.report.dto.request.UserPenaltyCreateRequest;
import com.gamerin.backend.domain.report.dto.response.UserPenaltyResponse;
import com.gamerin.backend.domain.report.entity.PenaltyType;
import com.gamerin.backend.domain.report.entity.UserPenalty;
import com.gamerin.backend.domain.report.repository.ReportRepository;
import com.gamerin.backend.domain.report.repository.UserPenaltyRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserStatus;
import com.gamerin.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 유저 제재 부여/해제 및 만료 자동 해제 로직 단위 테스트
 */
@ExtendWith(MockitoExtension.class)
class UserPenaltyServiceTest {

    @Mock
    private UserPenaltyRepository userPenaltyRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ReportRepository reportRepository;
    @Mock
    private AdminAuditLogRepository adminAuditLogRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private UserPenaltyService userPenaltyService;

    private User admin;
    private User targetUser;
    private UUID adminId;
    private UUID targetUserId;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        targetUserId = UUID.randomUUID();

        admin = User.createLocal("admin@gamerin.com", "admin", "어드민", "hash");
        ReflectionTestUtils.setField(admin, "id", adminId);

        targetUser = User.createLocal("user@gamerin.com", "baduser", "불량유저", "hash");
        ReflectionTestUtils.setField(targetUser, "id", targetUserId);
    }

    @Test
    @DisplayName("7일 정지 제재 부여 시 대상 유저 상태가 SUSPENDED로 변경되고 endAt이 계산된다")
    void createPenalty_suspension7d_success() {
        // given
        UserPenaltyCreateRequest request = new UserPenaltyCreateRequest(
                PenaltyType.SUSPENSION_7D, "스팸 도배", 7, null
        );

        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(userRepository.findById(targetUserId)).willReturn(Optional.of(targetUser));
        given(userPenaltyRepository.save(any(UserPenalty.class))).willAnswer(invocation -> {
            UserPenalty p = invocation.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });

        // when
        UserPenaltyResponse response = userPenaltyService.createPenalty(adminId, targetUserId, request);

        // then
        assertThat(response).isNotNull();
        assertThat(response.penaltyType()).isEqualTo(PenaltyType.SUSPENSION_7D);
        assertThat(response.isActive()).isTrue();
        assertThat(targetUser.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        verify(refreshTokenRepository).findAllByUserIdAndRevokedAtIsNull(targetUserId);
        verify(adminAuditLogRepository).save(any());
    }

    @Test
    @DisplayName("관리자 본인 계정에 제재를 부여하려고 하면 400 예외가 발생한다")
    void createPenalty_selfPenalty_throwsException() {
        // given
        UserPenaltyCreateRequest request = new UserPenaltyCreateRequest(
                PenaltyType.SUSPENSION_7D, "셀프 제재", 7, null
        );
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));

        // when & then
        assertThatThrownBy(() -> userPenaltyService.createPenalty(adminId, adminId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("관리자 본인 계정에는 제재를 부여할 수 없습니다.");
    }

    @Test
    @DisplayName("제재 수동 해제 시 잔여 정지 제재가 없으면 계정이 ACTIVE로 복구된다")
    void revokePenalty_restoresUserToActive() {
        // given
        UUID penaltyId = UUID.randomUUID();
        UserPenalty penalty = UserPenalty.create(
                targetUser, null, PenaltyType.SUSPENSION_7D, "스팸", OffsetDateTime.now().plusDays(7), admin
        );
        ReflectionTestUtils.setField(penalty, "id", penaltyId);
        targetUser.suspend();

        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(userPenaltyRepository.findById(penaltyId)).willReturn(Optional.of(penalty));
        given(userPenaltyRepository.save(any(UserPenalty.class))).willReturn(penalty);
        given(userPenaltyRepository.existsActiveSuspensionByUserId(targetUserId)).willReturn(false);

        // when
        UserPenaltyResponse response = userPenaltyService.revokePenalty(adminId, targetUserId, penaltyId);

        // then
        assertThat(response.isActive()).isFalse();
        assertThat(targetUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(adminAuditLogRepository).save(any());
    }

    @Test
    @DisplayName("만료 시점이 지난 제재는 배치 로직에서 비활성화되고 계정이 복구된다")
    void releaseExpiredPenalties_success() {
        // given
        targetUser.suspend();
        UserPenalty expiredPenalty = UserPenalty.create(
                targetUser, null, PenaltyType.SUSPENSION_7D, "스팸", OffsetDateTime.now().minusMinutes(5), admin
        );
        ReflectionTestUtils.setField(expiredPenalty, "id", UUID.randomUUID());

        given(userPenaltyRepository.findExpiredPenalties(any(OffsetDateTime.class)))
                .willReturn(List.of(expiredPenalty));
        given(userPenaltyRepository.existsActiveSuspensionByUserId(targetUserId))
                .willReturn(false);

        // when
        int releasedCount = userPenaltyService.releaseExpiredPenalties();

        // then
        assertThat(releasedCount).isEqualTo(1);
        assertThat(expiredPenalty.isActive()).isFalse();
        assertThat(targetUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }
}