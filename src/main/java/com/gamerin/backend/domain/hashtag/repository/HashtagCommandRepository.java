package com.gamerin.backend.domain.hashtag.repository;

import java.nio.ByteBuffer;
import java.util.UUID;

import org.hibernate.Session;
import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;

@Repository
public class HashtagCommandRepository {

    private final EntityManager entityManager;
    private volatile Boolean h2Database;

    public HashtagCommandRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public UUID findOrCreate(String displayName, String normalizedName) {
        if (isH2Database()) {
            return findOrCreateForH2(displayName, normalizedName);
        }

        UUID candidateId = UUID.randomUUID();
        entityManager.createNativeQuery("""
                insert into hashtags(id, display_name, normalized_name, created_at)
                values (:id, :displayName, :normalizedName, current_timestamp)
                on conflict (normalized_name) do nothing
                """)
                .setParameter("id", candidateId)
                .setParameter("displayName", displayName)
                .setParameter("normalizedName", normalizedName)
                .executeUpdate();

        Object id = entityManager.createNativeQuery("""
                select id
                from hashtags
                where normalized_name = :normalizedName
                """)
                .setParameter("normalizedName", normalizedName)
                .getSingleResult();
        return toUuid(id);
    }

    public void attachToPost(UUID postId, UUID hashtagId) {
        if (isH2Database()) {
            attachToPostForH2(postId, hashtagId);
            return;
        }

        entityManager.createNativeQuery("""
                insert into post_hashtags(id, post_id, hashtag_id, created_at)
                values (:id, :postId, :hashtagId, current_timestamp)
                on conflict (post_id, hashtag_id) do nothing
                """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("postId", postId)
                .setParameter("hashtagId", hashtagId)
                .executeUpdate();
    }

    private UUID findOrCreateForH2(String displayName, String normalizedName) {
        UUID existingId = findId(normalizedName);
        if (existingId != null) {
            return existingId;
        }

        UUID candidateId = UUID.randomUUID();
        entityManager.createNativeQuery("""
                insert into hashtags(id, display_name, normalized_name, created_at)
                values (:id, :displayName, :normalizedName, current_timestamp)
                """)
                .setParameter("id", candidateId)
                .setParameter("displayName", displayName)
                .setParameter("normalizedName", normalizedName)
                .executeUpdate();
        return candidateId;
    }

    private void attachToPostForH2(UUID postId, UUID hashtagId) {
        Number count = (Number) entityManager.createNativeQuery("""
                select count(*)
                from post_hashtags
                where post_id = :postId and hashtag_id = :hashtagId
                """)
                .setParameter("postId", postId)
                .setParameter("hashtagId", hashtagId)
                .getSingleResult();
        if (count.longValue() > 0) {
            return;
        }

        entityManager.createNativeQuery("""
                insert into post_hashtags(id, post_id, hashtag_id, created_at)
                values (:id, :postId, :hashtagId, current_timestamp)
                """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("postId", postId)
                .setParameter("hashtagId", hashtagId)
                .executeUpdate();
    }

    private UUID findId(String normalizedName) {
        java.util.List<?> ids = entityManager.createNativeQuery("""
                select id
                from hashtags
                where normalized_name = :normalizedName
                """)
                .setParameter("normalizedName", normalizedName)
                .getResultList();
        return ids.isEmpty() ? null : toUuid(ids.getFirst());
    }

    private boolean isH2Database() {
        Boolean cached = h2Database;
        if (cached != null) {
            return cached;
        }

        boolean detected = entityManager.unwrap(Session.class).doReturningWork(connection ->
                "H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())
        );
        h2Database = detected;
        return detected;
    }

    private UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof byte[] bytes && bytes.length == 16) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
        return UUID.fromString(value.toString());
    }
}
