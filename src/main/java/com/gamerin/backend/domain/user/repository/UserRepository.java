package com.gamerin.backend.domain.user.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserStatus;

import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Transactional(readOnly = true)
    @Query("select u from User u left join fetch u.profile where u.id = :id")
    Optional<User> findWithProfileById(@Param("id") UUID id);

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id and u.deletedAt is null")
    Optional<User> findActiveByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    Optional<User> findByHandle(String handle);

    Optional<User> findByHandleAndDeletedAtIsNull(String handle);

    boolean existsByHandle(String handle);

    @Query("""
        select u
        from User u
        where u.deletedAt is null
          and u.handle in :handles
        """)
    List<User> findActiveByHandleIn(@Param("handles") Collection<String> handles);

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
        @Param("playerName") String playerName);

    @Query(value = """
        SELECT EXISTS (
            SELECT 1
            FROM user_profiles up
            WHERE up.user_id <> :userId
              AND LOWER(up.game_stats -> 'R6' ->> 'accountId') = LOWER(:accountId)
              AND COALESCE((up.game_stats -> 'R6' ->> 'connected')::boolean, false) = true
        )
        """, nativeQuery = true)
    boolean existsConnectedR6AccountIdByOtherUser(
        @Param("userId") UUID userId,
        @Param("accountId") String accountId);

    @Query(value = """
        SELECT EXISTS (
            SELECT 1
            FROM user_profiles up
            WHERE up.user_id <> :userId
              AND up.game_stats -> 'RIOT' ->> 'puuid' = :puuid
              AND COALESCE((up.game_stats -> 'RIOT' ->> 'connected')::boolean, false) = true
        )
        """, nativeQuery = true)
    boolean existsConnectedRiotPuuidByOtherUser(
        @Param("userId") UUID userId,
        @Param("puuid") String puuid);

    @Query("""
        select u
        from User u
        where u.deletedAt is null
          and u.id <> :viewerId
          and (
              lower(u.handle) like lower(concat('%', :keyword, '%'))
              or lower(u.nickname) like lower(concat('%', :keyword, '%'))
          )
        order by u.nickname asc, u.handle asc
        """)
    List<User> searchMessageRecipients(
        @Param("viewerId") UUID viewerId,
        @Param("keyword") String keyword,
        Pageable pageable);

    // [추가] 어드민 유저 관리: 핸들/닉네임 키워드 검색 및 상태 필터링 페이징 조회
    @Query(value = """
        SELECT u FROM User u
        LEFT JOIN FETCH u.profile
        WHERE (:query IS NULL OR :query = ''
               OR LOWER(u.handle) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(u.nickname) LIKE LOWER(CONCAT('%', :query, '%')))
          AND (:status IS NULL OR u.status = :status)
        """, countQuery = """
        SELECT COUNT(u) FROM User u
        WHERE (:query IS NULL OR :query = ''
               OR LOWER(u.handle) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(u.nickname) LIKE LOWER(CONCAT('%', :query, '%')))
          AND (:status IS NULL OR u.status = :status)
        """)
    Page<User> searchUsersForAdmin(
        @Param("query") String query,
        @Param("status") UserStatus status,
        Pageable pageable);

    // [추가] 어드민 유저 관리: 핸들/닉네임 키워드 검색 + 상태 + 활성 제재 여부 페이징 조회
    @Query(value = """
        SELECT u FROM User u
        LEFT JOIN FETCH u.profile
        WHERE (:query IS NULL OR :query = ''
               OR LOWER(u.handle) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(u.nickname) LIKE LOWER(CONCAT('%', :query, '%')))
          AND (:status IS NULL OR u.status = :status)
          AND (:hasSanction IS NULL
               OR (:hasSanction = true
                   AND EXISTS (SELECT p FROM UserPenalty p WHERE p.user.id = u.id AND p.isActive = true))
               OR (:hasSanction = false
                   AND NOT EXISTS (SELECT p FROM UserPenalty p WHERE p.user.id = u.id AND p.isActive = true)))
        """, countQuery = """
        SELECT COUNT(u) FROM User u
        WHERE (:query IS NULL OR :query = ''
               OR LOWER(u.handle) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(u.nickname) LIKE LOWER(CONCAT('%', :query, '%')))
          AND (:status IS NULL OR u.status = :status)
          AND (:hasSanction IS NULL
               OR (:hasSanction = true
                   AND EXISTS (SELECT p FROM UserPenalty p WHERE p.user.id = u.id AND p.isActive = true))
               OR (:hasSanction = false
                   AND NOT EXISTS (SELECT p FROM UserPenalty p WHERE p.user.id = u.id AND p.isActive = true)))
        """)
    Page<User> searchUsersForAdmin(
        @Param("query") String query,
        @Param("status") UserStatus status,
        @Param("hasSanction") Boolean hasSanction,
        Pageable pageable);
}
