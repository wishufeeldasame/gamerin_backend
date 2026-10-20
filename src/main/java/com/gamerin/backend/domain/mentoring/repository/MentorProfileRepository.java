package com.gamerin.backend.domain.mentoring.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentorStatus;

import jakarta.persistence.LockModeType;

public interface MentorProfileRepository extends JpaRepository<MentorProfile, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select mentor from MentorProfile mentor where mentor.userId = :userId")
    java.util.Optional<MentorProfile> findByIdForUpdate(@Param("userId") UUID userId);

    /**
     * 정산 완료 건수를 DB 값 기준으로 원자적으로 1 증가시킨다.
     * 락(findByIdForUpdate) 이후에도 이미 영속성 컨텍스트에 로드된 엔티티는 오래된 값일 수 있어
     * getMenteeCount() + 1 방식은 증가분이 유실될 수 있다. 반드시 이 메서드를 사용한다.
     * (clearAutomatically를 쓰지 않는 이유: 같은 트랜잭션의 다른 엔티티 변경이 detach되는 것을 막기 위해)
     */
    @Modifying
    @Query("update MentorProfile m set m.menteeCount = m.menteeCount + 1 where m.userId = :userId")
    int incrementMenteeCount(@Param("userId") UUID userId);

    long countByStatus(MentorStatus status);

    Page<MentorProfile> findByStatus(MentorStatus status, Pageable pageable);
}