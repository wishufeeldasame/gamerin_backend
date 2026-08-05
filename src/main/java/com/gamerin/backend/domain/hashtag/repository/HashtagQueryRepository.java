package com.gamerin.backend.domain.hashtag.repository;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.gamerin.backend.domain.hashtag.model.HashtagPostCursor;
import com.gamerin.backend.domain.hashtag.model.HashtagSummary;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@Repository
public class HashtagQueryRepository {

    private final EntityManager entityManager;
    private final PostRepository postRepository;

    public HashtagQueryRepository(EntityManager entityManager, PostRepository postRepository) {
        this.entityManager = entityManager;
        this.postRepository = postRepository;
    }

    public List<Post> findActivePosts(
            String normalizedName,
            HashtagPostCursor cursor,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                select p.id
                from post_hashtags ph
                join hashtags h on h.id = ph.hashtag_id
                join posts p on p.id = ph.post_id
                join users author on author.id = p.author_id
                where h.normalized_name = :normalizedName
                  and p.deleted_at is null
                  and author.deleted_at is null
                """);
        if (cursor != null) {
            sql.append("""
                    and (
                        p.created_at < :cursorCreatedAt
                        or (p.created_at = :cursorCreatedAt and p.id < :cursorPostId)
                    )
                    """);
        }
        sql.append(" order by p.created_at desc, p.id desc limit :limit");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("normalizedName", normalizedName);
        if (cursor != null) {
            query.setParameter("cursorCreatedAt", Timestamp.from(cursor.createdAt().toInstant()));
            query.setParameter("cursorPostId", cursor.postId());
        }
        query.setParameter("limit", limit);

        return reorderPosts(toUuidList(query.getResultList()));
    }

    public List<HashtagSummary> findActiveSuggestions(String normalizedPrefix, int limit) {
        String escapedPrefix = escapeLike(normalizedPrefix) + "%";
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                select h.id, h.display_name, count(distinct p.id) as active_post_count
                from hashtags h
                join post_hashtags ph on ph.hashtag_id = h.id
                join posts p on p.id = ph.post_id and p.deleted_at is null
                join users author on author.id = p.author_id and author.deleted_at is null
                where h.normalized_name like :prefix escape '\\'
                group by h.id, h.display_name, h.normalized_name
                order by
                    case when h.normalized_name = :exactName then 0 else 1 end,
                    active_post_count desc,
                    h.normalized_name asc,
                    h.id asc
                limit :limit
                """)
                .setParameter("prefix", escapedPrefix)
                .setParameter("exactName", normalizedPrefix)
                .setParameter("limit", limit)
                .getResultList();

        List<HashtagSummary> results = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            results.add(new HashtagSummary(
                    toUuid(row[0]),
                    row[1].toString(),
                    ((Number) row[2]).longValue()
            ));
        }
        return results;
    }

    private List<Post> reorderPosts(List<UUID> postIds) {
        if (postIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, Post> postsById = new HashMap<>();
        for (Post post : postRepository.findAllByIds(postIds)) {
            postsById.put(post.getId(), post);
        }

        List<Post> ordered = new ArrayList<>(postIds.size());
        for (UUID postId : postIds) {
            Post post = postsById.get(postId);
            if (post != null) {
                ordered.add(post);
            }
        }
        return ordered;
    }

    private List<UUID> toUuidList(List<?> values) {
        return values.stream().map(this::toUuid).toList();
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

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
