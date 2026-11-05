CREATE TABLE IF NOT EXISTS mileage_wallets (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    balance BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 마일리지 거래 내역 로그 테이블
CREATE TABLE IF NOT EXISTS mileage_transactions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    amount BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    type VARCHAR(50) NOT NULL,
    description TEXT,
    reference_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_mileage_transactions_user_id ON mileage_transactions(user_id);
CREATE INDEX idx_mileage_transactions_created_at ON mileage_transactions(created_at);