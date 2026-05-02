package com.gamerin.backend.domain.post.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.post.entity.PostLike;

public interface PostLikeRepository extends JpaRepository<PostLike, UUID> {

    boolean existsByPostIdAndUserId(UUID postId, UUID userId);

    Optional<PostLike> findByPostIdAndUserId(UUID postId, UUID userId);

    @Query("""
        select pl.post.id
        from PostLike pl
        where pl.user.id = :userId
          and pl.post.id in :postIds
        """)
    List<UUID> findLikedPostIds(
            @Param("userId") UUID userId,
            @Param("postIds") Collection<UUID> postIds
    );
}
