package com.gamerin.backend.domain.report.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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
        // 실제 서비스가 호출하는 비관적 락 메서드(findActiveByIdForUpdate)로 스텁 수정
        given(userRepository.findActiveByIdForUpdate(targetUserId)).willReturn(Optional.of(targetUser));
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
        // 잠금 순서 일원화에 따른 실제 호출 메서드들로 스텁 수정
        given(userRepository.findActiveByIdForUpdate(targetUserId)).willReturn(Optional.of(targetUser));
        given(userPenaltyRepository.findByIdForUpdate(penaltyId)).willReturn(Optional.of(penalty));
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
        UserPenalty expiredPenalty = expiredPenalty(targetUser, PenaltyType.SUSPENSION_7D);

        given(userPenaltyRepository.findExpiredPenalties(any(OffsetDateTime.class)))
                .willReturn(List.of(expiredPenalty));
        // 스케줄러에서 유저 락 획득 후 계정 복구하므로 스텁 추가
        given(userRepository.findByIdForUpdate(targetUserId))
                .willReturn(Optional.of(targetUser));
        given(userPenaltyRepository.deactivateIfActive(expiredPenalty.getId())).willReturn(1);
        given(userPenaltyRepository.existsActiveSuspensionByUserId(targetUserId))
                .willReturn(false);

        // when
        int releasedCount = userPenaltyService.releaseExpiredPenalties();

        // then
        assertThat(releasedCount).isEqualTo(1);
        verify(userPenaltyRepository).deactivateIfActive(expiredPenalty.getId());
        assertThat(targetUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("탈퇴한 유저의 만료 제재도 비활성화되고 유저는 DELETED 상태로 유지되며 반환값은 실제 해제 건수와 같다")
    void releaseExpiredPenalties_withdrawnUser_deactivatesPenaltyOnly() {
        // given: 정지 중 탈퇴한 유저와 정지 중인 일반 유저의 만료 제재
        targetUser.suspend();
        targetUser.withdraw();
        UserPenalty withdrawnPenalty = expiredPenalty(targetUser, PenaltyType.SUSPENSION_7D);

        UUID otherUserId = UUID.randomUUID();
        User otherUser = User.createLocal("other@gamerin.com", "other", "다른유저", "hash");
        ReflectionTestUtils.setField(otherUser, "id", otherUserId);
        otherUser.suspend();
        UserPenalty otherPenalty = expiredPenalty(otherUser, PenaltyType.SUSPENSION_3D);

        given(userPenaltyRepository.findExpiredPenalties(any(OffsetDateTime.class)))
                .willReturn(List.of(withdrawnPenalty, otherPenalty));
        given(userRepository.findByIdForUpdate(targetUserId)).willReturn(Optional.of(targetUser));
        given(userRepository.findByIdForUpdate(otherUserId)).willReturn(Optional.of(otherUser));
        given(userPenaltyRepository.deactivateIfActive(withdrawnPenalty.getId())).willReturn(1);
        given(userPenaltyRepository.deactivateIfActive(otherPenalty.getId())).willReturn(1);
        given(userPenaltyRepository.existsActiveSuspensionByUserId(otherUserId)).willReturn(false);

        // when
        int releasedCount = userPenaltyService.releaseExpiredPenalties();

        // then
        assertThat(releasedCount).isEqualTo(2);
        verify(userPenaltyRepository).deactivateIfActive(withdrawnPenalty.getId());
        verify(userPenaltyRepository).deactivateIfActive(otherPenalty.getId());
        assertThat(targetUser.getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(otherUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(userRepository, never()).findActiveByIdForUpdate(any());
    }

    @Test
    @DisplayName("조회 이후 수동 해제되어 이미 비활성인 제재는 해제 건수에 포함하지 않고 계정 상태도 바꾸지 않는다")
    void releaseExpiredPenalties_alreadyRevoked_notCounted() {
        // given
        targetUser.suspend();
        UserPenalty revokedMeanwhile = expiredPenalty(targetUser, PenaltyType.SUSPENSION_7D);

        given(userPenaltyRepository.findExpiredPenalties(any(OffsetDateTime.class)))
                .willReturn(List.of(revokedMeanwhile));
        given(userRepository.findByIdForUpdate(targetUserId)).willReturn(Optional.of(targetUser));
        given(userPenaltyRepository.deactivateIfActive(revokedMeanwhile.getId())).willReturn(0);

        // when
        int releasedCount = userPenaltyService.releaseExpiredPenalties();

        // then
        assertThat(releasedCount).isZero();
        assertThat(targetUser.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        verify(userPenaltyRepository, never()).existsActiveSuspensionByUserId(any());
    }

    private UserPenalty expiredPenalty(User user, PenaltyType penaltyType) {
        UserPenalty penalty = UserPenalty.create(
                user, null, penaltyType, "스팸", OffsetDateTime.now().minusMinutes(5), admin
        );
        ReflectionTestUtils.setField(penalty, "id", UUID.randomUUID());
        return penalty;
    }
}
