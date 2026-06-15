package com.gamerin.backend.domain.follow.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.follow.entity.Follow;

public interface FollowRepository extends JpaRepository<Follow, UUID> {

    boolean existsByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    Optional<Follow> findByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    @Query("""
            SELECT COUNT(f)
            FROM Follow f
            JOIN f.follower follower
            WHERE f.followee.id = :followeeId
              AND follower.deletedAt IS NULL
            """)
    long countActiveFollowersByFolloweeId(@Param("followeeId") UUID followeeId);

    @Query("""
            SELECT COUNT(f)
            FROM Follow f
            JOIN f.followee followee
            WHERE f.follower.id = :followerId
              AND followee.deletedAt IS NULL
            """)
    long countActiveFollowingByFollowerId(@Param("followerId") UUID followerId);

    @Query(value = """
            SELECT CAST(f.id AS VARCHAR)
            FROM follows f
            JOIN users u ON u.id = f.follower_id
            WHERE f.followee_id = :followeeId
              AND u.deleted_at IS NULL
            ORDER BY f.created_at DESC, f.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<String> findFollowerPageIds(
            @Param("followeeId") UUID followeeId,
            @Param("limit") int limit
    );

    @Query(value = """
            SELECT CAST(f.id AS VARCHAR)
            FROM follows f
            JOIN users u ON u.id = f.follower_id
            WHERE f.followee_id = :followeeId
              AND u.deleted_at IS NULL
              AND (
                  f.created_at < :cursorCreatedAt
                  OR (f.created_at = :cursorCreatedAt AND f.id < :cursorId)
              )
            ORDER BY f.created_at DESC, f.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<String> findFollowerPageIdsBefore(
            @Param("followeeId") UUID followeeId,
            @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            @Param("limit") int limit
    );

    @Query(value = """
            SELECT CAST(f.id AS VARCHAR)
            FROM follows f
            JOIN users u ON u.id = f.followee_id
            WHERE f.follower_id = :followerId
              AND u.deleted_at IS NULL
            ORDER BY f.created_at DESC, f.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<String> findFollowingPageIds(
            @Param("followerId") UUID followerId,
            @Param("limit") int limit
    );

    @Query(value = """
            SELECT CAST(f.id AS VARCHAR)
            FROM follows f
            JOIN users u ON u.id = f.followee_id
            WHERE f.follower_id = :followerId
              AND u.deleted_at IS NULL
              AND (
                  f.created_at < :cursorCreatedAt
                  OR (f.created_at = :cursorCreatedAt AND f.id < :cursorId)
              )
            ORDER BY f.created_at DESC, f.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<String> findFollowingPageIdsBefore(
            @Param("followerId") UUID followerId,
            @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            @Param("limit") int limit
    );

    @Query("""
            SELECT f
            FROM Follow f
            JOIN FETCH f.follower follower
            LEFT JOIN FETCH follower.profile
            JOIN FETCH f.followee followee
            LEFT JOIN FETCH followee.profile
            WHERE f.id IN :ids
            """)
    List<Follow> findAllWithUsersByIdIn(@Param("ids") Collection<UUID> ids);

    @Query("""
            SELECT f.followee.id
            FROM Follow f
            WHERE f.follower.id = :followerId
              AND f.followee.id IN :followeeIds
            """)
    List<UUID> findFolloweeIdsByFollowerIdAndFolloweeIdIn(
            @Param("followerId") UUID followerId,
            @Param("followeeIds") Collection<UUID> followeeIds
    );
}
