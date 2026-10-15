package com.gamerin.backend.domain.post.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.post.entity.PostComment;

import jakarta.persistence.LockModeType;

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

    @Query("""
        select pc.id
        from PostComment pc
        where pc.post.id = :postId
        """)
    List<UUID> findIdsByPostId(@Param("postId") UUID postId);

    // 댓글의 deleted_at은 신고 숨김에서만 설정된다(사용자 삭제는 하드 삭제).
    @Query("""
        select pc.id
        from PostComment pc
        where pc.deletedAt is not null
          and pc.deletedAt <= :hiddenCutoff
        order by pc.deletedAt asc, pc.id asc
        """)
    List<UUID> findHiddenIdsBefore(@Param("hiddenCutoff") OffsetDateTime hiddenCutoff);

    // 잠금 순서(report_counts → post_comments)를 지키도록 신고 카운트를 먼저 잠근 뒤 호출한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select pc
        from PostComment pc
        where pc.id = :id
          and pc.deletedAt is not null
          and pc.deletedAt <= :hiddenCutoff
        """)
    Optional<PostComment> findHiddenHardDeleteCandidateForUpdate(
            @Param("id") UUID id,
            @Param("hiddenCutoff") OffsetDateTime hiddenCutoff
    );
}
