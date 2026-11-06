package com.gamerin.backend.domain.post.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.Session;
import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;

/** post_views·posts.view_count 쓰기. 모두 단일 SQL이라 동시 요청에도 원자적이다(#183). */
@Repository
public class PostViewCommandRepository {

    private final EntityManager entityManager;
    private volatile Boolean h2Database;

    public PostViewCommandRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** 마지막 반영 후 24시간이 지난 행만 갱신한다. 1이면 이번 조회를 센다. 같은 행의 동시 갱신은 행 잠금으로 직렬화된다. */
    public int refreshIfExpired(UUID postId, UUID userId, OffsetDateTime now, OffsetDateTime cutoff) {
        return entityManager.createNativeQuery("""
                update post_views
                set viewed_at = :now
                where post_id = :postId and user_id = :userId and viewed_at <= :cutoff
                """)
                .setParameter("now", now)
                .setParameter("postId", postId)
                .setParameter("userId", userId)
                .setParameter("cutoff", cutoff)
                .executeUpdate();
    }

    /** 첫 조회면 1, 이미 행이 있으면(동시 삽입 포함) 0. */
    public int insertIfAbsent(UUID postId, UUID userId, OffsetDateTime now) {
        // H2(테스트)는 ON CONFLICT를 지원하지 않는다. 운영(PostgreSQL)은 유니크 충돌을 DB가 걸러 동시 삽입에도 안전하다.
        String sql = isH2Database()
                ? """
                insert into post_views(post_id, user_id, viewed_at)
                select :postId, :userId, :now
                where not exists (select 1 from post_views where post_id = :postId and user_id = :userId)
                """
                : """
                insert into post_views(post_id, user_id, viewed_at)
                values (:postId, :userId, :now)
                on conflict (post_id, user_id) do nothing
                """;
        return entityManager.createNativeQuery(sql)
                .setParameter("postId", postId)
                .setParameter("userId", userId)
                .setParameter("now", now)
                .executeUpdate();
    }

    public int increaseViewCount(UUID postId) {
        return entityManager.createNativeQuery(
                "update posts set view_count = view_count + 1 where id = :postId and deleted_at is null")
                .setParameter("postId", postId)
                .executeUpdate();
    }

    private boolean isH2Database() {
        Boolean cached = h2Database;
        if (cached != null) {
            return cached;
        }
        boolean detected = entityManager.unwrap(Session.class).doReturningWork(
                connection -> "H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName()));
        h2Database = detected;
        return detected;
    }
}
