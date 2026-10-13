-- 카카오페이 결제 주문 (docs/kakaopay 04 문서 6-5·6-6절, 05 문서 7절)
-- id는 카카오페이 partner_order_id이자 원장 CHARGE/CHARGE_CANCEL의 reference_id다.
CREATE TABLE payments (
    id UUID PRIMARY KEY,
    buyer_id UUID NOT NULL REFERENCES users(id),
    request_id UUID NOT NULL,
    provider VARCHAR(20) NOT NULL,
    purpose VARCHAR(30) NOT NULL,
    product_code VARCHAR(50) NOT NULL,
    item_name VARCHAR(100) NOT NULL,
    amount BIGINT NOT NULL,
    cid VARCHAR(50) NOT NULL,
    tid VARCHAR(50),
    approved_aid VARCHAR(50),
    canceled_aid VARCHAR(50),
    payment_method_type VARCHAR(20),
    status VARCHAR(20) NOT NULL,
    status_changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- READY 재응답용 결제창 URL. 로그에 남기지 않는다.
    next_redirect_pc_url TEXT,
    next_redirect_mobile_url TEXT,
    ready_at TIMESTAMP WITH TIME ZONE,
    provider_created_at TIMESTAMP WITH TIME ZONE,
    approved_at TIMESTAMP WITH TIME ZONE,
    canceled_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_payments_status CHECK (status IN (
        'CREATED', 'READY', 'APPROVING', 'APPROVED', 'CANCELING',
        'CANCELED', 'FAILED', 'EXPIRED', 'NEEDS_REVIEW'
    )),
    CONSTRAINT chk_payments_purpose CHECK (purpose IN ('MILEAGE_CHARGE', 'MEMBERSHIP_PASS')),
    CONSTRAINT chk_payments_amount CHECK (amount > 0),
    CONSTRAINT uk_payments_tid UNIQUE (tid),
    CONSTRAINT uk_payments_buyer_request UNIQUE (buyer_id, request_id)
);

-- 복구 스케줄러용
CREATE INDEX idx_payments_status_changed_at ON payments (status, status_changed_at);
-- 결제 목록 (createdAt, id) 커서용
CREATE INDEX idx_payments_buyer_created_at ON payments (buyer_id, created_at, id);

-- 결제당 적립·회수 각 1회. 기존 무료 충전 행은 reference_id가 NULL이라 충돌하지 않는다.
CREATE UNIQUE INDEX uk_mileage_transactions_charge_reference
    ON mileage_transactions (type, reference_id)
    WHERE type IN ('CHARGE', 'CHARGE_CANCEL');
