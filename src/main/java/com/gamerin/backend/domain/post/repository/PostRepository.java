package com.gamerin.backend.domain.post.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.post.entity.Post;

public interface PostRepository extends JpaRepository<Post, UUID> {

    Optional<Post> findByIdAndDeletedAtIsNull(UUID id);

    @Query("""
        select p
        from Post p
        where p.deletedAt is not null
          and p.deletedAt <= :cutoff
        order by p.deletedAt asc, p.id asc
        """)
    List<Post> findHardDeleteCandidates(@Param("cutoff") OffsetDateTime cutoff);

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
