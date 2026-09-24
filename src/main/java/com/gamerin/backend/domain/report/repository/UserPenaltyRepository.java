package com.gamerin.backend.domain.report.repository;

import com.gamerin.backend.domain.report.entity.UserPenalty;

import org.antlr.v4.runtime.atn.SemanticContext.AND;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

//유저 제재 이력 저장, 미들웨어용 현재 정지 여부 확인, 만료 제재 해제 스케줄러용 조회.
public interface UserPenaltyRepository extends JpaRepository<UserPenalty, UUID> {

    // 유저의 현재 활성화된 제재 존재 여부 (접근 차단 미들웨어용)
    boolean existsByUserIdAndIsActiveTrue(UUID userId);

    // 제재 해제 동시 요청 및 만료 스케줄러 경합 시 중복 해제/감사로그 방지를 위한 비관적 쓰기 락 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserPenalty p where p.id = :id")
    java.util.Optional<UserPenalty> findByIdForUpdate(@Param("id") UUID id);

    // 경고(WARNING)를 제외한 실제 활성 '정지' 제재가 존재하는지 확인 (미들웨어 차단 및 해제 복구 판단용)
    @Query("SELECT COUNT(p) > 0 FROM UserPenalty p WHERE p.user.id = :userId AND p.isActive = true AND p.penaltyType <> com.gamerin.backend.domain.report.entity.PenaltyType.WARNING")
    boolean existsActiveSuspensionByUserId(@Param("userId") UUID userId);

    // 특정 유저의 현재 제재 정보 조회
    List<UserPenalty> findByUserIdAndIsActiveTrue(UUID userId);

    // 특정 유저의 전체 제재 내역 조회
    Page<UserPenalty> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    // 현재 활성화된 전체 제재 목록 (admin_users.png 대응)
    Page<UserPenalty> findByIsActiveTrueOrderByCreatedAtDesc(Pageable pageable);

    // 제재 만료 시간이 지난 활성 제재 조회
    @Query("SELECT p FROM UserPenalty p WHERE p.isActive = true AND p.endAt IS NOT NULL AND p.endAt<=:now")
    List<UserPenalty> findExpiredPenalties(@Param("now") OffsetDateTime now);

    // 현재 활성화된 제재를 보유한 고유 유저 수 집계
    @Query("SELECT COUNT(DISTINCT p.user.id) FROM UserPenalty p WHERE p.isActive = true")
    long countDistinctUserIdByIsActiveTrue();
}