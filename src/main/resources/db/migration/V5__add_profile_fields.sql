-- 사용자 프로필 편집 기능을 위해 누락된 컬럼 추가
ALTER TABLE user_profiles ADD COLUMN cover_image_url TEXT;
ALTER TABLE user_profiles ADD COLUMN location VARCHAR(100);
ALTER TABLE user_profiles ADD COLUMN website TEXT;