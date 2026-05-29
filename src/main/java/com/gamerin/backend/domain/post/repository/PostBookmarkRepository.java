package com.gamerin.backend.domain.post.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.post.entity.PostBookmark;

public interface PostBookmarkRepository extends JpaRepository<PostBookmark, UUID> {

    boolean existsByPostIdAndUserId(UUID postId, UUID userId);

    Optional<PostBookmark> findByPostIdAndUserId(UUID postId, UUID userId);

    @Query("""
        select pb.post.id
        from PostBookmark pb
        where pb.user.id = :userId
          and pb.post.id in :postIds
        """)
    List<UUID> findBookmarkedPostIds(
            @Param("userId") UUID userId,
            @Param("postIds") Collection<UUID> postIds
    );
}
