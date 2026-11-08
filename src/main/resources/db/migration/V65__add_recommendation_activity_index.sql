CREATE INDEX idx_users_active_recommendation_activity
    ON users (COALESCE(last_login_at, created_at) DESC, id DESC)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;
