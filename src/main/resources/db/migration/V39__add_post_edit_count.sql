-- KP-C3 (#92): 멤버십 게시물 1회 수정 제한을 위한 수정 횟수 컬럼 및 범위 제약 추가
ALTER TABLE posts
    ADD COLUMN edit_count SMALLINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_posts_edit_count CHECK (edit_count BETWEEN 0 AND 1);