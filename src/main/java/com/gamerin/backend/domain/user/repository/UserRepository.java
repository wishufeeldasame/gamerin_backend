package com.gamerin.backend.domain.user.repository;

import com.gamerin.backend.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByHandle(String handle);
    boolean existsByHandle(String handle);

    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    @Query(value = """
        SELECT EXISTS (
            SELECT 1
            FROM user_profiles up
            WHERE up.user_id <> :userId
              AND up.game_stats -> 'PUBG' ->> 'playerName' = :playerName
              AND COALESCE((up.game_stats -> 'PUBG' ->> 'connected')::boolean, false) = true
        )
        """, nativeQuery = true)
    boolean existsConnectedPubgPlayerNameByOtherUser(
            @Param("userId") UUID userId,
            @Param("playerName") String playerName
    );
}
