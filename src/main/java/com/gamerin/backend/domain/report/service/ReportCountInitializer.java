package com.gamerin.backend.domain.report.service;

import java.util.UUID;

import com.gamerin.backend.domain.report.entity.ReportCount;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.repository.ReportCountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신고 카운트 레코드 원자적 초기화 전용 컴포넌트
 * (독립 트랜잭션을 사용하여 동시 생성 충돌 시 메인 트랜잭션의 rollback-only 전파 차단)
 */
@Component
public class ReportCountInitializer {

    private final ReportCountRepository reportCountRepository;

    public ReportCountInitializer(ReportCountRepository reportCountRepository) {
        this.reportCountRepository = reportCountRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void initIfNotExists(ReportTargetType targetType, UUID targetId) {
        if (!reportCountRepository.existsByTargetTypeAndTargetId(targetType, targetId)) {
            try {
                reportCountRepository.saveAndFlush(ReportCount.create(targetType, targetId));
            } catch (DataIntegrityViolationException ignored) {
                // 다른 트랜잭션이 먼저 생성하여 유니크 제약 충돌 발생 시 이 별도 트랜잭션만 롤백되고 메인 트랜잭션은 안전함
            }
        }
    }
}