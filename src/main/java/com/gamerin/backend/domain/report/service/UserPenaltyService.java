package com.gamerin.backend.domain.report.service;

import com.gamerin.backend.domain.admin.entity.AdminAuditLog;
import com.gamerin.backend.domain.admin.repository.AdminAuditLogRepository;
import com.gamerin.backend.domain.auth.entity.RefreshToken;
import com.gamerin.backend.domain.auth.repository.RefreshTokenRepository;
import com.gamerin.backend.domain.report.dto.request.UserPenaltyCreateRequest;
import com.gamerin.backend.domain.report.dto.response.UserPenaltyResponse;
import com.gamerin.backend.domain.report.entity.PenaltyType;
import com.gamerin.backend.domain.report.entity.Report;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.entity.UserPenalty;
import com.gamerin.backend.domain.report.repository.ReportRepository;
import com.gamerin.backend.domain.report.repository.UserPenaltyRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserStatus;
import com.gamerin.backend.domain.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 어드민 유저 제재 부여/해제, 감사 로그 적재, 토큰 즉시 무효화 및 만료 제재 일괄 해제 서비스
 */
@Service
@Transactional(readOnly = true)
public class UserPenaltyService {

    private final UserPenaltyRepository userPenaltyRepository;
    private final UserRepository userRepository;
    private final ReportRepository reportRepository;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public UserPenaltyService(
            UserPenaltyRepository userPenaltyRepository,
            UserRepository userRepository,
            ReportRepository reportRepository,
            AdminAuditLogRepository adminAuditLogRepository,
            RefreshTokenRepository refreshTokenRepository) {
        this.userPenaltyRepository = userPenaltyRepository;
        this.userRepository = userRepository;
        this.reportRepository = reportRepository;
        this.adminAuditLogRepository = adminAuditLogRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /**
     * 어드민 유저 제재(경고, 7일/30일/영구 정지) 부여
     */
    @Transactional
    public UserPenaltyResponse createPenalty(UUID adminId, UUID targetUserId, UserPenaltyCreateRequest request) {
        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "어드민 계정을 찾을 수 없습니다."));

        // 1. 자기 자신 제재 방지
        if (admin.getId().equals(targetUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "관리자 본인 계정에는 제재를 부여할 수 없습니다.");
        }

        // 2. 대상 유저 존재 확인
        // 2. 대상 유저 비관적 쓰기 락 획득 (잠금 순서: User -> UserPenalty 일관성 유지)
        User targetUser = userRepository.findActiveByIdForUpdate(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "제재 대상 유저를 찾을 수 없습니다."));

        if (targetUser.getStatus() == UserStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미 탈퇴한 계정에는 제재를 부여할 수 없습니다.");
        }

        // 3. 연관 신고 내역 확인 (선택 사항)
        Report report = null;
        if (request.reportId() != null) {
            report = reportRepository.findById(request.reportId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "연관된 신고 내역을 찾을 수 없습니다. ID: " + request.reportId()));
        }

        // 4. 제재 만료 시점(endAt) 계산
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime endAt = calculateEndAt(request.penaltyType(), request.durationDays(), now);

        // 5. 제재 내역 저장
        UserPenalty penalty = UserPenalty.create(
                targetUser,
                report,
                request.penaltyType(),
                request.reason(),
                endAt,
                admin);
        UserPenalty savedPenalty = userPenaltyRepository.save(penalty);

        // 6. 경고가 아닌 정지 제재인 경우 유저 상태를 SUSPENDED로 변경하고 활성 RefreshToken 즉시 무효화
        if (request.penaltyType() != PenaltyType.WARNING) {
            targetUser.suspend();
            userRepository.save(targetUser);

            refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(targetUserId)
                    .forEach(RefreshToken::revoke);
        }

        // 7. 어드민 감사 로그 적재
        String actionType = (request.penaltyType() == PenaltyType.WARNING) ? "USER_WARNING" : "USER_BAN";
        String logDetails = String.format("제재 유형: %s, 사유: %s, 만료일: %s",
                request.penaltyType().getDescription(),
                request.reason(),
                endAt != null ? endAt.toString() : "영구");

        adminAuditLogRepository.save(AdminAuditLog.create(
                admin, actionType, ReportTargetType.USER, targetUserId, null, logDetails));

        return UserPenaltyResponse.from(savedPenalty);
    }

    /**
     * 어드민 유저 제재 수동 해제
     */
    @Transactional
    public UserPenaltyResponse revokePenalty(UUID adminId, UUID targetUserId, UUID penaltyId) {
        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "어드민 계정을 찾을 수 없습니다."));

        // 1. 잠금 순서 일원화: 대상 유저를 먼저 비관적 락으로 잠가 신규 제재 생성과의 경합 방지
        User targetUser = userRepository.findActiveByIdForUpdate(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "제재 대상 유저를 찾을 수 없습니다."));

        // 2. 해제 대상 제재 비관적 락 조회
        UserPenalty penalty = userPenaltyRepository.findByIdForUpdate(penaltyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 제재 내역을 찾을 수 없습니다."));

        if (!penalty.getUser().getId().equals(targetUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "요청한 대상 유저의 제재 내역이 아닙니다.");
        }

        if (!penalty.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미 해제되었거나 만료된 제재입니다.");
        }

        // 3. 제재 비활성화
        penalty.deactivate();
        UserPenalty updatedPenalty = userPenaltyRepository.save(penalty);

        // 4. 유저 락이 유지된 상태에서 다른 활성 정지 제재 여부를 안전하게 검사하고 계정 복구
        boolean hasActiveSuspension = userPenaltyRepository.existsActiveSuspensionByUserId(targetUserId);
        if (!hasActiveSuspension && targetUser.getStatus() == UserStatus.SUSPENDED) {
            targetUser.activate();
            userRepository.save(targetUser);
        }

        // 5. 어드민 감사 로그 적재
        String logDetails = String.format("제재 수동 해제 (제재 ID: %s, 제재 유형: %s)",
                penaltyId, penalty.getPenaltyType().getDescription());

        adminAuditLogRepository.save(AdminAuditLog.create(
                admin, "USER_UNBAN", ReportTargetType.USER, targetUserId, null, logDetails));

        return UserPenaltyResponse.from(updatedPenalty);
    }

    /**
     * 특정 유저의 제재 내역 페이징 조회
     */
    public Page<UserPenaltyResponse> getUserPenalties(UUID targetUserId, Pageable pageable) {
        return userPenaltyRepository.findByUserIdOrderByCreatedAtDesc(targetUserId, pageable)
                .map(UserPenaltyResponse::from);
    }

    /**
     * 현재 활성 제재 전체 목록 페이징 조회
     */
    public Page<UserPenaltyResponse> getActivePenalties(Pageable pageable) {
        return userPenaltyRepository.findByIsActiveTrueOrderByCreatedAtDesc(pageable)
                .map(UserPenaltyResponse::from);
    }

    /**
     * 정지 만료 시간이 도래한 활성 제재 일괄 해제 (배치 스케줄러 호출용)
     */
    @Transactional
    public int releaseExpiredPenalties() {
        OffsetDateTime now = OffsetDateTime.now();
        List<UserPenalty> expiredPenalties = userPenaltyRepository.findExpiredPenalties(now);

        if (expiredPenalties.isEmpty()) {
            return 0;
        }

        for (UserPenalty penalty : expiredPenalties) {
            penalty.deactivate();
            userPenaltyRepository.save(penalty);

            UUID userId = penalty.getUser().getId();
            // 대상 유저 락 획득 후 다른 활성 정지 여부 검증 및 상태 복구
            userRepository.findActiveByIdForUpdate(userId).ifPresent(user -> {
                boolean hasActiveSuspension = userPenaltyRepository.existsActiveSuspensionByUserId(userId);
                if (!hasActiveSuspension && user.getStatus() == UserStatus.SUSPENDED) {
                    user.activate();
                    userRepository.save(user);
                }
            });
        }

        return expiredPenalties.size();
    }

    /**
     * 제재 유형별 종료 일시 계산 헬퍼
     */
    private OffsetDateTime calculateEndAt(PenaltyType penaltyType, Integer customDays, OffsetDateTime now) {
        return switch (penaltyType) {
            case WARNING -> null;
            case SUSPENSION_7D -> {
                int days = (customDays != null && customDays > 0) ? customDays : 7;
                yield now.plusDays(days);
            }
            case SUSPENSION_30D -> {
                int days = (customDays != null && customDays > 0) ? customDays : 30;
                yield now.plusDays(days);
            }
            case PERMANENT_BAN -> null;
        };
    }
}