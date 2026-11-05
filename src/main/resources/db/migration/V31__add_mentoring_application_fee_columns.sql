-- V31: 멘토링 신청 수수료 요율(bp) 및 수수료 금액 컬럼 추가 (KP-C1 / #90 F1)
-- fee_rate_bp: 기본 요율 1000bp (10%), 허용 범위 0 ~ 10000bp (0% ~ 100%)
-- fee_amount: 정산 시점에 계산되어 멘토에게 공제된 수수료 금액을 기록 (미정산/취소/환불 시 NULL)

ALTER TABLE mentoring_applications
    ADD COLUMN fee_rate_bp INT NOT NULL DEFAULT 1000 
        CONSTRAINT chk_mentoring_applications_fee_rate_bp 
        CHECK (fee_rate_bp BETWEEN 0 AND 10000),
    ADD COLUMN fee_amount BIGINT NULL;