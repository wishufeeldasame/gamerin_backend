package com.gamerin.backend.domain.hashtag.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.gamerin.backend.domain.hashtag.model.HashtagBackfillCursor;
import com.gamerin.backend.domain.post.entity.Post;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

@Repository
public class HashtagBackfillRepository {

    private final EntityManager entityManager;

    public HashtagBackfillRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<Post> findNextBatch(HashtagBackfillCursor cursor, int limit) {
        StringBuilder jpql = new StringBuilder("""
                select p
                from Post p
                join fetch p.author author
                where p.deletedAt is null
                  and author.deletedAt is null
                  and p.content is not null
                  and p.content like '%#%'
                """);
        if (cursor != null) {
            jpql.append("""
                    and (
                        p.createdAt > :cursorCreatedAt
                        or (p.createdAt = :cursorCreatedAt and p.id > :cursorPostId)
                    )
                    """);
        }
        jpql.append(" order by p.createdAt asc, p.id asc");

        TypedQuery<Post> query = entityManager.createQuery(jpql.toString(), Post.class);
        if (cursor != null) {
            query.setParameter("cursorCreatedAt", cursor.createdAt());
            query.setParameter("cursorPostId", cursor.postId());
        }
        query.setMaxResults(limit);
        return query.getResultList();
    }
}
