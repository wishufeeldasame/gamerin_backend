package com.gamerin.backend.domain.upload.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.post.entity.PostVideoJobStatus;
import com.gamerin.backend.domain.upload.entity.UploadJob;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface UploadJobRepository extends JpaRepository<UploadJob, UUID> {
    Optional<UploadJob> findByUserIdAndKindAndClientKey(UUID userId, UploadJob.Kind kind, UUID clientKey);
    boolean existsByUserIdAndKindAndClientKey(UUID userId, UploadJob.Kind kind, UUID clientKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from UploadJob j where j.id = :id")
    Optional<UploadJob> findForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select j from UploadJob j where j.status = :status order by j.createdAt, j.id")
    List<UploadJob> findOldestForUpdate(@Param("status") PostVideoJobStatus status, Pageable page);

    List<UploadJob> findByStatus(PostVideoJobStatus status);
    List<UploadJob> findByStatusInAndPayloadIsNotNull(List<PostVideoJobStatus> statuses, Pageable page);
}
