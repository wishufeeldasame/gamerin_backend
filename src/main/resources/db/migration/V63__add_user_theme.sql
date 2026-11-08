-- 사용자 테마(#182). NULL은 아직 고르지 않음이며, 프론트가 기기 설정이나 기본값(다크)을 쓴다.
ALTER TABLE users ADD COLUMN theme VARCHAR(10);
