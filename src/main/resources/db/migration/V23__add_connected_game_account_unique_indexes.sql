-- Match the existing duplicate queries, including R6 case folding and connected-state predicates.
-- Existing duplicate owners must be resolved explicitly before deployment; do not discard their links here.
CREATE UNIQUE INDEX uq_user_profiles_connected_r6_account
    ON user_profiles (LOWER(game_stats -> 'R6' ->> 'accountId'))
    WHERE COALESCE((game_stats -> 'R6' ->> 'connected')::boolean, false) = true;

CREATE UNIQUE INDEX uq_user_profiles_connected_riot_puuid
    ON user_profiles ((game_stats -> 'RIOT' ->> 'puuid'))
    WHERE COALESCE((game_stats -> 'RIOT' ->> 'connected')::boolean, false) = true;
