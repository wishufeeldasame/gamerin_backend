package com.gamerin.backend.domain.post.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.post.entity.PostVideoJob;
import com.gamerin.backend.domain.post.entity.PostVideoJobStatus;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface PostVideoJobRepository extends JpaRepository<PostVideoJob, UUID> {

    /** 가장 오래된 대기 작업을 잠근다. 잠긴 행은 건너뛰어(SKIP LOCKED) 워커가 늘어도 같은 작업을 두 번 잡지 않는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select job from PostVideoJob job where job.status = :status order by job.createdAt asc")
    List<PostVideoJob> findOldestForUpdate(@Param("status") PostVideoJobStatus status, Pageable pageable);

    List<PostVideoJob> findByStatus(PostVideoJobStatus status);
}
