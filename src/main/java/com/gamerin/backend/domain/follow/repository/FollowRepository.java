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

    long countByFollowerId(UUID followerId);

    long countByFolloweeId(UUID followeeId);

    @Query(value = """
            select f.id
            from follows f
            join users u on u.id = f.follower_id
            where f.followee_id = :followeeId
              and u.deleted_at is null
            order by f.created_at desc, f.id desc
            limit :limit
            """, nativeQuery = true)
    List<UUID> findFollowerPageIds(
            @Param("followeeId") UUID followeeId,
            @Param("limit") int limit
    );

    @Query(value = """
            select f.id
            from follows f
            join users u on u.id = f.follower_id
            where f.followee_id = :followeeId
              and u.deleted_at is null
              and (
                  f.created_at < :cursorCreatedAt
                  or (f.created_at = :cursorCreatedAt and f.id < :cursorId)
              )
            order by f.created_at desc, f.id desc
            limit :limit
            """, nativeQuery = true)
    List<UUID> findFollowerPageIdsBefore(
            @Param("followeeId") UUID followeeId,
            @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            @Param("limit") int limit
    );

    @Query(value = """
            select f.id
            from follows f
            join users u on u.id = f.followee_id
            where f.follower_id = :followerId
              and u.deleted_at is null
            order by f.created_at desc, f.id desc
            limit :limit
            """, nativeQuery = true)
    List<UUID> findFollowingPageIds(
            @Param("followerId") UUID followerId,
            @Param("limit") int limit
    );

    @Query(value = """
            select f.id
            from follows f
            join users u on u.id = f.followee_id
            where f.follower_id = :followerId
              and u.deleted_at is null
              and (
                  f.created_at < :cursorCreatedAt
                  or (f.created_at = :cursorCreatedAt and f.id < :cursorId)
              )
            order by f.created_at desc, f.id desc
            limit :limit
            """, nativeQuery = true)
    List<UUID> findFollowingPageIdsBefore(
            @Param("followerId") UUID followerId,
            @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            @Param("limit") int limit
    );

    @Query("""
            select f
            from Follow f
            join fetch f.follower follower
            left join fetch follower.profile
            join fetch f.followee followee
            left join fetch followee.profile
            where f.id in :ids
            """)
    List<Follow> findAllWithUsersByIdIn(@Param("ids") Collection<UUID> ids);

    @Query("""
            select f.followee.id
            from Follow f
            where f.follower.id = :followerId
              and f.followee.id in :followeeIds
            """)
    List<UUID> findFolloweeIdsByFollowerIdAndFolloweeIdIn(
            @Param("followerId") UUID followerId,
            @Param("followeeIds") Collection<UUID> followeeIds
    );
}
