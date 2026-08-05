package com.gamerin.backend.domain.search.repository;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.search.model.AccountSearchCursor;
import com.gamerin.backend.domain.search.model.PostSearchCursor;
import com.gamerin.backend.domain.user.entity.User;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@Repository
public class SearchQueryRepository {

    private final EntityManager entityManager;
    private final PostRepository postRepository;

    public SearchQueryRepository(EntityManager entityManager, PostRepository postRepository) {
        this.entityManager = entityManager;
        this.postRepository = postRepository;
    }

    public List<User> findActiveAccounts(
            String keyword,
            AccountSearchCursor cursor,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                select u.id
                from users u
                where u.deleted_at is null
                  and (
                      lower(u.handle) like :keyword escape '\\'
                      or lower(u.nickname) like :keyword escape '\\'
                  )
                """);
        if (cursor != null) {
            sql.append("""
                    and (
                        lower(u.handle) > :cursorHandle
                        or (lower(u.handle) = :cursorHandle and u.id > :cursorUserId)
                    )
                    """);
        }
        sql.append(" order by lower(u.handle) asc, u.id asc limit :limit");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("keyword", containsPattern(keyword));
        if (cursor != null) {
            query.setParameter("cursorHandle", cursor.normalizedHandle());
            query.setParameter("cursorUserId", cursor.userId());
        }
        query.setParameter("limit", limit);
        return reorderUsers(toUuidList(query.getResultList()));
    }

    public List<Post> findActivePosts(
            String keyword,
            PostSearchCursor cursor,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                select p.id
                from posts p
                join users author on author.id = p.author_id
                where p.deleted_at is null
                  and author.deleted_at is null
                  and lower(coalesce(p.content, '')) like :keyword escape '\\'
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
        query.setParameter("keyword", containsPattern(keyword));
        if (cursor != null) {
            query.setParameter("cursorCreatedAt", Timestamp.from(cursor.createdAt().toInstant()));
            query.setParameter("cursorPostId", cursor.postId());
        }
        query.setParameter("limit", limit);
        return reorderPosts(toUuidList(query.getResultList()));
    }

    private List<User> reorderUsers(List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }

        List<User> users = entityManager.createQuery("""
                select distinct u
                from User u
                left join fetch u.profile
                where u.id in :ids
                """, User.class)
                .setParameter("ids", userIds)
                .getResultList();
        Map<UUID, User> usersById = new HashMap<>();
        for (User user : users) {
            usersById.put(user.getId(), user);
        }

        List<User> ordered = new ArrayList<>(userIds.size());
        for (UUID userId : userIds) {
            User user = usersById.get(userId);
            if (user != null) {
                ordered.add(user);
            }
        }
        return ordered;
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

    private String containsPattern(String keyword) {
        return "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%";
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
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
}
