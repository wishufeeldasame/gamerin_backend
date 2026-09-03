package com.gamerin.backend.domain.mention.repository;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import org.hibernate.Session;
import org.springframework.stereotype.Repository;

import com.gamerin.backend.domain.mention.entity.UserMention;

import jakarta.persistence.EntityManager;

@Repository
public class MentionCommandRepository {

    private final EntityManager entityManager;
    private volatile Boolean h2Database;

    public MentionCommandRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public MentionAttachResult attachToPost(UUID postId, UUID mentionedUserId) {
        return attach("post_id", postId, mentionedUserId);
    }

    public MentionAttachResult attachToComment(UUID commentId, UUID mentionedUserId) {
        return attach("comment_id", commentId, mentionedUserId);
    }

    public void deleteByCommentId(UUID commentId) {
        entityManager.createNativeQuery("delete from user_mentions where comment_id = :sourceId")
                .setParameter("sourceId", commentId)
                .executeUpdate();
    }

    private MentionAttachResult attach(String sourceColumn, UUID sourceId, UUID mentionedUserId) {
        UUID existingId = findId(sourceColumn, sourceId, mentionedUserId);
        if (existingId != null) {
            return result(existingId, false);
        }

        UUID candidateId = UUID.randomUUID();
        int inserted;
        if (isH2Database()) {
            inserted = insertForH2(sourceColumn, candidateId, sourceId, mentionedUserId);
        } else {
            inserted = insertForPostgres(sourceColumn, candidateId, sourceId, mentionedUserId);
        }

        UUID mentionId = inserted == 1
                ? candidateId
                : findRequiredId(sourceColumn, sourceId, mentionedUserId);
        return result(mentionId, inserted == 1);
    }

    private int insertForPostgres(
            String sourceColumn,
            UUID id,
            UUID sourceId,
            UUID mentionedUserId
    ) {
        String sql = """
                insert into user_mentions(id, %s, mentioned_user_id, created_at)
                values (:id, :sourceId, :mentionedUserId, current_timestamp)
                on conflict (%s, mentioned_user_id) do nothing
                """.formatted(sourceColumn, sourceColumn);
        return entityManager.createNativeQuery(sql)
                .setParameter("id", id)
                .setParameter("sourceId", sourceId)
                .setParameter("mentionedUserId", mentionedUserId)
                .executeUpdate();
    }

    private int insertForH2(
            String sourceColumn,
            UUID id,
            UUID sourceId,
            UUID mentionedUserId
    ) {
        String sql = """
                insert into user_mentions(id, %s, mentioned_user_id, created_at)
                values (:id, :sourceId, :mentionedUserId, current_timestamp)
                """.formatted(sourceColumn);
        return entityManager.createNativeQuery(sql)
                .setParameter("id", id)
                .setParameter("sourceId", sourceId)
                .setParameter("mentionedUserId", mentionedUserId)
                .executeUpdate();
    }

    private UUID findRequiredId(String sourceColumn, UUID sourceId, UUID mentionedUserId) {
        UUID id = findId(sourceColumn, sourceId, mentionedUserId);
        if (id == null) {
            throw new IllegalStateException("Mention relation was not created.");
        }
        return id;
    }

    private UUID findId(String sourceColumn, UUID sourceId, UUID mentionedUserId) {
        String sql = """
                select id
                from user_mentions
                where %s = :sourceId and mentioned_user_id = :mentionedUserId
                """.formatted(sourceColumn);
        List<?> ids = entityManager.createNativeQuery(sql)
                .setParameter("sourceId", sourceId)
                .setParameter("mentionedUserId", mentionedUserId)
                .getResultList();
        return ids.isEmpty() ? null : toUuid(ids.getFirst());
    }

    private MentionAttachResult result(UUID mentionId, boolean created) {
        return new MentionAttachResult(
                entityManager.getReference(UserMention.class, mentionId),
                created
        );
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

    public record MentionAttachResult(UserMention mention, boolean created) {
    }
}
