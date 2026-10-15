package com.gamerin.backend.domain.post.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.post.entity.Post;

import jakarta.persistence.LockModeType;

public interface PostRepository extends JpaRepository<Post, UUID> {

    Optional<Post> findByIdAndDeletedAtIsNull(UUID id);

    @Query("""
        select p
        from Post p
        join fetch p.author author
        where p.id = :id
          and p.deletedAt is null
          and author.deletedAt is null
        """)
    Optional<Post> findAccessibleById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select p
        from Post p
        where p.id = :id
          and p.deletedAt is null
        """)
    Optional<Post> findActiveByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
        select p
        from Post p
        join fetch p.author author
        where p.id = :id
          and p.deletedAt is null
          and author.deletedAt is null
        """)
    Optional<Post> findAccessibleByIdForShare(@Param("id") UUID id);

    // 정리 후보 id. 보존 기간이 짧은 쪽 기준이라 상위 집합이며, 실제 대상은 findHardDeleteCandidateForUpdate로 다시 거른다.
    @Query("""
        select p.id
        from Post p
        where p.deletedAt is not null
          and p.deletedAt <= :cutoff
        order by p.deletedAt asc, p.id asc
        """)
    List<UUID> findSoftDeletedIdsBefore(@Param("cutoff") OffsetDateTime cutoff);

    // 신고로 숨긴 글(report_counts.is_hidden)은 hiddenCutoff, 사용자가 삭제한 글은 cutoff 기준으로 정리한다.
    // 잠금 순서(report_counts → posts)를 지키도록 신고 카운트를 먼저 잠근 뒤 호출한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select p
        from Post p
        where p.id = :id
          and p.deletedAt is not null
          and (
                (p.deletedAt <= :hiddenCutoff and exists (
                    select 1 from ReportCount rc
                    where rc.targetType = com.gamerin.backend.domain.report.entity.ReportTargetType.POST
                      and rc.targetId = p.id
                      and rc.isHidden = true))
                or (p.deletedAt <= :cutoff and not exists (
                    select 1 from ReportCount rc
                    where rc.targetType = com.gamerin.backend.domain.report.entity.ReportTargetType.POST
                      and rc.targetId = p.id
                      and rc.isHidden = true))
          )
        """)
    Optional<Post> findHardDeleteCandidateForUpdate(
            @Param("id") UUID id,
            @Param("cutoff") OffsetDateTime cutoff,
            @Param("hiddenCutoff") OffsetDateTime hiddenCutoff
    );

    // 신고 숨김·복구용. 영속성 컨텍스트의 오래된 값이 아니라 DB 값을 기준으로 갱신한다.
    @Modifying
    @Query("update Post p set p.commentCount = p.commentCount - 1 where p.id = :id and p.commentCount > 0")
    int decreaseCommentCount(@Param("id") UUID id);

    @Modifying
    @Query("update Post p set p.commentCount = p.commentCount + 1 where p.id = :id")
    int increaseCommentCount(@Param("id") UUID id);

    long countByAuthorIdAndDeletedAtIsNull(UUID authorId);

    @Query("""
        select count(distinct p.id)
        from Post p
        join PostMedia pm on pm.post = p
        where p.author.id = :authorId
          and p.deletedAt is null
          and pm.deletedAt is null
        """)
    long countMediaPostsByAuthorId(@Param("authorId") UUID authorId);

    @Query("""
        select p
        from Post p
        where p.id in :ids
        """)
    List<Post> findAllByIds(@Param("ids") Collection<UUID> ids);
}
