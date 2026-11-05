-- 이메일별 인증번호 발송을 직렬화하는 잠금 행을 저장한다(#100).
-- 이전 발송 이력이 없는 이메일도 행 잠금을 걸 수 있도록 이메일당 한 행을 둔다.
CREATE TABLE IF NOT EXISTS email_verification_locks (
    email VARCHAR(255) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
