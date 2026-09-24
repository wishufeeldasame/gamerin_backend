package com.gamerin.backend.domain.report.service;

import java.util.UUID;

import com.gamerin.backend.domain.report.entity.ReportCount;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.repository.ReportCountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 신고 카운트 레코드 원자적 초기화 전용 컴포넌트
 * (트랜잭션 경계 바깥에서 유니크 제약 충돌 예외를 처리하여 UnexpectedRollbackException 방지)
 */
@Component
public class ReportCountInitializer {

    private final ReportCountRepository reportCountRepository;
    private final TransactionTemplate requiresNewTxTemplate;

    public ReportCountInitializer(ReportCountRepository reportCountRepository,
                                  PlatformTransactionManager transactionManager) {
        this.reportCountRepository = reportCountRepository;
        this.requiresNewTxTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * ReportCount 레코드가 없을 때 안전하게 원자적 생성
     * (동시 생성 충돌 시 독립 트랜잭션은 롤백되고, 예외는 트랜잭션 바깥에서 삼켜져 메인 트랜잭션 보호)
     */
    public void initIfNotExists(ReportTargetType targetType, UUID targetId) {
        if (!reportCountRepository.existsByTargetTypeAndTargetId(targetType, targetId)) {
            try {
                // 독립 트랜잭션(REQUIRES_NEW) 내부에서 저장 시도
                requiresNewTxTemplate.execute(status -> {
                    reportCountRepository.saveAndFlush(ReportCount.create(targetType, targetId));
                    return null;
                });
            } catch (DataIntegrityViolationException ignored) {
                // 트랜잭션 경계 바깥에서 catch하므로 UnexpectedRollbackException이 발생하지 않음
            }
        }
    }
}