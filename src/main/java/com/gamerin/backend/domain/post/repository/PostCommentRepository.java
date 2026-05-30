package com.gamerin.backend.domain.post.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.post.entity.PostComment;

public interface PostCommentRepository extends JpaRepository<PostComment, UUID> {

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
