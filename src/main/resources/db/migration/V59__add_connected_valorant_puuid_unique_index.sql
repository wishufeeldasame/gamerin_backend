-- 발로란트(HenrikDev) 계정은 연동 중인 한 사용자만 가질 수 있다. 중복 조회 쿼리와 같은 조건을 쓴다.
CREATE UNIQUE INDEX uq_user_profiles_connected_valorant_puuid
    ON user_profiles ((game_stats -> 'VALORANT' ->> 'puuid'))
    WHERE COALESCE((game_stats -> 'VALORANT' ->> 'connected')::boolean, false) = true;
