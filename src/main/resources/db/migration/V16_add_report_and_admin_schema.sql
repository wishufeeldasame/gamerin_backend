-- ==========================================
-- 0. 신고 코드용 시퀀스 생성 (RPT-1001, RPT-1002...)
-- ==========================================
CREATE SEQUENCE IF NOT EXISTS report_code_seq START WITH 1001 INCREMENT BY 1;

-- ==========================================
-- 1. 신고 접수 테이블 (reports)
-- ==========================================
CREATE TABLE reports (
    id UUID PRIMARY KEY,
    report_code VARCHAR(30) NOT NULL UNIQUE DEFAULT ('RPT-' || lpad(nextval('report_code_seq')::text, 4, '0')),
    reporter_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_type VARCHAR(20) NOT NULL, -- POST, COMMENT, USER, MENTORING, MESSAGE
    target_id UUID NOT NULL,
    target_snippet TEXT, -- 신고 접수 시점의 콘텐츠 스냅샷 (원본 삭제/수정 대비)
    reason_code VARCHAR(30) NOT NULL, -- PROFANITY, SPAM, IMPERSONATION, INAPPROPRIATE, OTHER
    details TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED', -- RECEIVED, IN_REVIEW, RESOLVED, REJECTED
    assigned_admin_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 인덱스 및 중복 신고 방지 제약조건
CREATE UNIQUE INDEX uk_reports_reporter_target ON reports (reporter_id, target_type, target_id);
CREATE INDEX idx_reports_target ON reports (target_type, target_id);
CREATE INDEX idx_reports_status ON reports (status);
CREATE INDEX idx_reports_code ON reports (report_code);

-- ==========================================
-- 2. 신고 누적 카운트 및 자동 숨김 테이블 (report_counts)
-- ==========================================
CREATE TABLE report_counts (
    id UUID PRIMARY KEY,
    target_type VARCHAR(20) NOT NULL,
    target_id UUID NOT NULL,
    report_count BIGINT NOT NULL DEFAULT 0,
    is_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_report_counts_target UNIQUE (target_type, target_id)
);

-- ==========================================
-- 3. 유저 제재 이력 테이블 (user_penalties)
-- ==========================================
CREATE TABLE user_penalties (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    report_id UUID REFERENCES reports(id) ON DELETE SET NULL, -- 제재 원인이 된 신고 건 (선택)
    penalty_type VARCHAR(30) NOT NULL, -- WARNING, SUSPENSION_7D, SUSPENSION_30D, PERMANENT_BAN
    reason TEXT NOT NULL,
    start_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_at TIMESTAMPTZ, -- NULL이면 영구 정지
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    administered_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 로그인 차단 검증 최적화 복합 인덱스
CREATE INDEX idx_user_penalties_user_active ON user_penalties (user_id, is_active);
CREATE INDEX idx_user_penalties_active_end ON user_penalties (is_active, end_at);

-- ==========================================
-- 4. 관리자 작업 이력 테이블 (admin_audit_logs)
-- ==========================================
CREATE TABLE admin_audit_logs (
    id UUID PRIMARY KEY,
    admin_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action_type VARCHAR(50) NOT NULL, -- CONTENT_HIDE, USER_BAN, REPORT_REJECT, CONTENT_RESTORE, FORCE_REFUND 등
    target_type VARCHAR(20) NOT NULL,
    target_id UUID NOT NULL,
    request_id VARCHAR(100),
    details TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_admin_audit_logs_admin ON admin_audit_logs (admin_id);

-- ==========================================
-- 5. 시스템 설정 테이블 (system_configs)
-- ==========================================
CREATE TABLE system_configs (
    config_key VARCHAR(50) PRIMARY KEY,
    config_value VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 피그마 admin_settings.png 기반 기본 설정값 5종 추가
INSERT INTO system_configs (config_key, config_value, description)
VALUES 
    ('AUTO_HIDE_ENABLED', 'true', '동일 콘텐츠 신고 임계값 초과 시 자동 숨김 여부'),
    ('AUTO_HIDE_THRESHOLD', '5', '자동 숨김 임계값 (신고 수)'),
    ('NEW_REPORT_ALERT', 'true', '새 신고 접수 시 담당자 알림 여부'),
    ('DEFAULT_SANCTION_LEVEL', 'WARNING', '기본 제재 수준 (WARNING, SUSPENSION_7D 등)'),
    ('RE_REVIEW_DEADLINE_DAYS', '7', '신고 재검토 기한 (일)')
ON CONFLICT (config_key) DO NOTHING;