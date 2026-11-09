-- Opt-in 게시물/DM 업로드. 입력 파일은 비공개 tmp 볼륨에 보관한다.
CREATE TABLE upload_jobs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind VARCHAR(10) NOT NULL,
    client_key UUID NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    payload TEXT,
    status VARCHAR(20) NOT NULL,
    result_id UUID,
    error_code VARCHAR(40),
    error_message TEXT,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    CONSTRAINT uk_upload_jobs_owner_kind_key UNIQUE(user_id, kind, client_key),
    CONSTRAINT ck_upload_jobs_kind CHECK (kind IN ('POST', 'MESSAGE')),
    CONSTRAINT ck_upload_jobs_status CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED'))
);
CREATE INDEX idx_upload_jobs_status_created_at ON upload_jobs(status, created_at, id);
