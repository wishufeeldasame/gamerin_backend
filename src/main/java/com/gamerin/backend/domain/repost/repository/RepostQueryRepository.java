package com.gamerin.backend.domain.repost.repository;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.gamerin.backend.domain.repost.model.RepostMetrics;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@Repository
public class RepostQueryRepository {

    private final EntityManager entityManager;

    public RepostQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Map<UUID, RepostMetrics> findMetrics(UUID viewerId, Collection<UUID> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }

        String viewerExpression = viewerId == null
                ? "0"
                : "max(case when repost.user_id = :viewerId then 1 else 0 end)";
        String sql = """
            select repost.post_id,
                   count(*) as repost_count,
                   %s as reposted_by_viewer
            from post_reposts repost
            join users repost_user on repost_user.id = repost.user_id
            where repost.post_id in (:postIds)
              and repost_user.deleted_at is null
            group by repost.post_id
            """.formatted(viewerExpression);

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("postIds", postIds);
        if (viewerId != null) {
            query.setParameter("viewerId", viewerId);
        }

        Map<UUID, RepostMetrics> metrics = new HashMap<>();
        for (Object rowValue : query.getResultList()) {
            Object[] row = (Object[]) rowValue;
            metrics.put(
                    toUuid(row[0]),
                    new RepostMetrics(
                            ((Number) row[1]).longValue(),
                            ((Number) row[2]).intValue() > 0
                    )
            );
        }
        return metrics;
    }

    private UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof byte[] bytes && bytes.length == 16) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
        return UUID.fromString(String.valueOf(value));
    }
}
