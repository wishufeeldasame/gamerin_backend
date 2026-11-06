-- 대표 게임(#179). 닉네임 옆 티어 엠블럼을 고를 연동 게임(GameType 이름)이며, NULL이면 표시하지 않는다.
-- 허용 게임은 서비스가 연동 여부로 검증하므로, 게임 추가(#178) 때 마이그레이션이 필요 없도록 CHECK는 두지 않는다.
ALTER TABLE user_profiles ADD COLUMN representative_game VARCHAR(20);
