package com.gamerin.backend.domain.post.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.post.entity.PostComment;

public interface PostCommentRepository extends JpaRepository<PostComment, UUID> {

    @Query("""
        select pc
        from PostComment pc
        join fetch pc.post post
        join fetch pc.author author
        where pc.id = :commentId
          and post.id = :postId
          and pc.deletedAt is null
          and post.deletedAt is null
        """)
    Optional<PostComment> findActiveByPostIdAndId(
            @Param("postId") UUID postId,
            @Param("commentId") UUID commentId
    );

    @Query("""
        select pc
        from PostComment pc
        join fetch pc.author author
        left join fetch author.profile
        where pc.post.id = :postId
          and pc.deletedAt is null
        order by pc.createdAt desc, pc.id desc
        """)
    List<PostComment> findActiveByPostId(@Param("postId") UUID postId);
}
