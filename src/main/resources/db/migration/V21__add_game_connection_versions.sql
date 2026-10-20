ALTER TABLE user_profiles
    ADD COLUMN game_connection_versions JSONB NOT NULL DEFAULT '{}';
