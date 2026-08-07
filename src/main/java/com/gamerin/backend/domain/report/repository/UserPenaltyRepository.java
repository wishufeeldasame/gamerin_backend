package com.gamerin.backend.domain.report.repository;

import com.gamerin.backend.domain.report.entity.UserPenalty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

//유저 제재 이력 저장, 미들웨어용 현재 정지 여부 확인, 만료 제재 해제 스케줄러용 조회.
public interface UserPenaltyRepository extends
        JpaRepository<UserPenalty, UUID> {

    // 유저의 현재 활성화된 제재 존재 여부 (접근 차단 미들웨어용)
    boolean existsByUserIdAndIsActiveTrue(UUID userId);

    // 특정 유저의 현재 제재 정보 조회
    List<UserPenalty> findByUserIdAndIsActiveTrue(UUID userId);

    // 특정 유저의 전체 제재 내역 조회
    Page<UserPenalty> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    // 현재 활성화된 전체 제재 목록 (admin_users.png 대응)
    Page<UserPenalty> findByIsActiveTrueOrderByCreatedAtDesc(Pageable pageable);

    // 제재 만료 시간이 지난 활성 제재 조회 (5단계 자동 해제 스케줄러용)

    @Query("SELECT p FROM UserPenalty p WHERE p.isActive = true AND p.endAt IS NOT NULL AND p.endAt<=:now")
    List<UserPenalty> findExpiredPenalties(@Param("now") OffsetDateTime now);

    // 현재 활성 제재 건수 (대시보드 통계용)
    long countByIsActiveTrue();
}