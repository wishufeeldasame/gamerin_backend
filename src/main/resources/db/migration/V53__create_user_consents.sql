-- 회원가입 시 동의한 약관 유형·버전·시각을 기록한다(#150). 탈퇴해도 users 행이 남으므로 기록도 보존한다.
CREATE TABLE IF NOT EXISTS user_consents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    consent_type VARCHAR(30) NOT NULL,
    version VARCHAR(30) NOT NULL,
    agreed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_user_consents_user_type_version UNIQUE (user_id, consent_type, version)
);
