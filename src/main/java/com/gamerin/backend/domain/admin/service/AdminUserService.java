package com.gamerin.backend.domain.admin.service;

import com.gamerin.backend.domain.admin.dto.response.AdminUserResponse;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.entity.UserPenalty;
import com.gamerin.backend.domain.report.repository.ReportRepository;
import com.gamerin.backend.domain.report.repository.UserPenaltyRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserStatus;
import com.gamerin.backend.domain.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AdminUserService {

    private final UserRepository userRepository;
    private final ReportRepository reportRepository;
    private final UserPenaltyRepository userPenaltyRepository;

    public AdminUserService(
            UserRepository userRepository,
            ReportRepository reportRepository,
            UserPenaltyRepository userPenaltyRepository) {
        this.userRepository = userRepository;
        this.reportRepository = reportRepository;
        this.userPenaltyRepository = userPenaltyRepository;
    }

    /**
     * 어드민 전용 사용자 목록 검색 및 페이징 조회
     */
    public Page<AdminUserResponse> getAdminUsers(String query, UserStatus status, Boolean hasSanction,
            Pageable pageable) {
        Page<User> usersPage = userRepository.searchUsersForAdmin(query, status, pageable);

        List<AdminUserResponse> content = usersPage.getContent().stream()
                .map(this::mapToAdminUserResponse)
                .filter(res -> {
                    if (hasSanction == null)
                        return true;
                    boolean hasActive = !"없음".equals(res.activeSanction());
                    return hasSanction ? hasActive : !hasActive;
                })
                .toList();

        return new PageImpl<>(content, pageable, usersPage.getTotalElements());
    }

    /**
     * 사용자 핸들(handle)로 상세 정보 단건 조회 (프론트 /admin/users/[handle] 대응)
     */
    public AdminUserResponse getAdminUserByHandle(String handle) {
        String cleanHandle = handle.startsWith("@") ? handle.substring(1) : handle;
        User user = userRepository.findByHandle(cleanHandle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 사용자를 찾을 수 없습니다: @" +
                        cleanHandle));
        return mapToAdminUserResponse(user);
    }

    /**
     * 사용자 UUID로 상세 정보 단건 조회
     */
    public AdminUserResponse getAdminUserById(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 사용자를 찾을 수 없습니다. ID: " +
                        userId));
        return mapToAdminUserResponse(user);
    }

    /**
     * User 엔티티에 신고 카운트와 활성 제재 정보를 결합하여 DTO로 변환
     */
    private AdminUserResponse mapToAdminUserResponse(User user) {
        // 1. 유저 대상 신고 수 집계
        long reportsCount = reportRepository.countByTargetTypeAndTargetId(ReportTargetType.USER, user.getId());

        // 2. 현재 활성 제재 정보 조회
        List<UserPenalty> activePenalties = userPenaltyRepository.findByUserIdAndIsActiveTrue(user.getId());
        String activeSanction = null;
        UUID activePenaltyId = null;

        if (!activePenalties.isEmpty()) {
            UserPenalty latestPenalty = activePenalties.get(0);
            activeSanction = latestPenalty.getPenaltyType().getDescription();
            activePenaltyId = latestPenalty.getId();
        }

        return AdminUserResponse.of(user, reportsCount, activeSanction, activePenaltyId);
    }
}