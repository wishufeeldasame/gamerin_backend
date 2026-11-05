package com.gamerin.backend.domain.repost.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.repost.entity.PostRepost;

public interface PostRepostRepository extends JpaRepository<PostRepost, UUID> {

    boolean existsByPostIdAndUserId(UUID postId, UUID userId);

    Optional<PostRepost> findByPostIdAndUserId(UUID postId, UUID userId);

    @Query("""
        select count(repost)
        from PostRepost repost
        where repost.post.id = :postId
          and repost.user.deletedAt is null
        """)
    long countActiveByPostId(@Param("postId") UUID postId);
}
