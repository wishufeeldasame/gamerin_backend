package com.gamerin.backend.domain.post.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.post.entity.PostMedia;

public interface PostMediaRepository extends JpaRepository<PostMedia, UUID> {

    List<PostMedia> findByPostIdOrderBySortOrderAscIdAsc(UUID postId);

    @Query("""
        select pm
        from PostMedia pm
        where pm.post.id = :postId
          and pm.deletedAt is null
        order by pm.sortOrder asc, pm.id asc
        """)
    List<PostMedia> findActiveByPostId(@Param("postId") UUID postId);

    @Query("""
        select pm
        from PostMedia pm
        where pm.post.id in :postIds
          and pm.deletedAt is null
        order by pm.post.id asc, pm.sortOrder asc, pm.id asc
        """)
    List<PostMedia> findActiveByPostIds(@Param("postIds") Collection<UUID> postIds);

    @Query("""
        select count(pm.id)
        from PostMedia pm
        join pm.post p
        where p.author.id = :authorId
          and p.deletedAt is null
          and pm.deletedAt is null
        """)
    long countActiveMediaByAuthorId(@Param("authorId") UUID authorId);
}
