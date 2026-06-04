-- 1. 멘토 프로필 테이블
CREATE TABLE IF NOT EXISTS mentor_profiles (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, INACTIVE
    about TEXT,
    rating_avg DECIMAL(3, 2) NOT NULL DEFAULT 0.00,
    review_count INT NOT NULL DEFAULT 0,
    mentee_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. 멘토링 프로그램(상품) 테이블
CREATE TABLE IF NOT EXISTS mentoring_programs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mentor_id UUID NOT NULL REFERENCES mentor_profiles(user_id) ON DELETE CASCADE,
    game_name VARCHAR(100) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT,
    available_time_desc VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, CLOSED
    price BIGINT NOT NULL DEFAULT 0,
    tags JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 멘토링 신청 내역 테이블 (에스크로 및 진행 상태 관리)
CREATE TABLE IF NOT EXISTS mentoring_applications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id UUID NOT NULL REFERENCES mentoring_programs(id) ON DELETE CASCADE,
    mentee_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    applied_mileage BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'APPLIED', -- APPLIED, ACCEPTED, REJECTED, ONGOING, COMPLETED, CANCELLED
    payment_status VARCHAR(30) NOT NULL DEFAULT 'PENDING', -- PENDING, ESCROW_HELD, SETTLED, REFUNDED
    message TEXT,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 4. 멘토링 리뷰 테이블
CREATE TABLE IF NOT EXISTS mentoring_reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL UNIQUE REFERENCES mentoring_applications(id) ON
DELETE CASCADE,
    mentor_id UUID NOT NULL REFERENCES mentor_profiles(user_id) ON DELETE CASCADE,
    mentee_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    rating INT NOT NULL CHECK (rating >= 1 AND rating <= 5),
    content TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_mentoring_programs_mentor_id ON mentoring_programs(mentor_id);
CREATE INDEX IF NOT EXISTS idx_mentoring_applications_mentee_id ON mentoring_applications(mentee_id);
CREATE INDEX IF NOT EXISTS idx_mentoring_reviews_mentor_id ON mentoring_reviews(mentor_id);
