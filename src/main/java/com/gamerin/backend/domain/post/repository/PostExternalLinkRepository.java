package com.gamerin.backend.domain.post.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.post.entity.PostExternalLink;

public interface PostExternalLinkRepository extends JpaRepository<PostExternalLink, UUID> {

    Optional<PostExternalLink> findByPostId(UUID postId);

    @Query("""
        select pel
        from PostExternalLink pel
        where pel.post.id in :postIds
        """)
    List<PostExternalLink> findByPostIds(@Param("postIds") Collection<UUID> postIds);
}
