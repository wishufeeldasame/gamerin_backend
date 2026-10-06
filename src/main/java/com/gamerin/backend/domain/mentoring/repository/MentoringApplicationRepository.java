package com.gamerin.backend.domain.mentoring.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.PaymentStatus;

public interface MentoringApplicationRepository extends JpaRepository<MentoringApplication, UUID> {

        // 멘티 ID로 신청 내역 조회 (페이징)
        Page<MentoringApplication> findByMenteeId(UUID menteeId, Pageable pageable);

        // 멘토 ID(program.mentor.id)로 신청 내역 조회 (페이징)
        Page<MentoringApplication> findByProgramMentorId(UUID mentorId, Pageable pageable);

        boolean existsByMenteeIdAndProgramIdAndStatusIn(UUID menteeId, UUID programId,
                        List<ApplicationStatus> statuses);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select application from MentoringApplication application where application.id = :id")
        java.util.Optional<MentoringApplication> findByIdForUpdate(@Param("id") UUID id);

        @Query("""
                        select application.id
                        from MentoringApplication application
                        where application.status = :status
                          and application.updatedAt < :threshold
                        order by application.updatedAt asc, application.id asc
                        """)
        List<UUID> findIdsByStatusAndUpdatedAtBefore(
                        @Param("status") ApplicationStatus status,
                        @Param("threshold") OffsetDateTime threshold);

        /**
         * 특정 프로그램에 에스크로가 활성화된 신청이 존재하는지 확인한다.
         * paymentStatus = ESCROW_HELD 인 신청이 하나라도 있으면 소프트 삭제를 거부해야 한다.
         * APPLIED, ACCEPTED, ONGOING, FINISHED 상태는 모두 ESCROW_HELD에 해당한다.
         */
        boolean existsByProgramIdAndPaymentStatus(UUID programId, PaymentStatus paymentStatus);

        /**
         * 특정 프로그램에 특정 결제 상태인 신청 건수를 조회한다 (동시성 정합성 검증용).
         */
        long countByProgramIdAndPaymentStatus(UUID programId, PaymentStatus paymentStatus);

        // 이번 달 생성된 신청 건수
        long countByCreatedAtAfter(OffsetDateTime dateTime);

        // 에스크로 보관 중인 마일리지 총액 합계
        @Query("SELECT COALESCE(SUM(a.appliedMileage), 0) FROM MentoringApplication a WHERE a.paymentStatus = 'ESCROW_HELD'")
        long sumEscrowHeldMileage();

        // 프로그램별 신청 건수(세션 수)
        long countByProgramId(UUID programId);

}
