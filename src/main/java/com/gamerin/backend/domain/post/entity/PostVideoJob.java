package com.gamerin.backend.domain.post.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 게시물 영상 비동기 처리 작업. 업로드 요청은 검증·검열 후 이 작업만 저장하고,
 * 워커가 재인코딩을 끝낸 뒤 게시물을 만든다. 그래서 처리 중인 영상은 피드에 노출되지 않는다.
 */
@Entity
@Table(name = "post_video_jobs")
public class PostVideoJob {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_tier", nullable = false, length = 10)
    private VideoQualityTier qualityTier;

    @Column(name = "input_path", nullable = false, columnDefinition = "TEXT")
    private String inputPath;

    @Column(name = "input_extension", nullable = false, length = 10)
    private String inputExtension;

    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PostVideoJobStatus status;

    @Column(name = "post_id")
    private UUID postId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    protected PostVideoJob() {
    }

    public static PostVideoJob queue(
            UUID id,
            UUID userId,
            String content,
            VideoQualityTier qualityTier,
            String inputPath,
            String inputExtension,
            String thumbnailUrl,
            OffsetDateTime now
    ) {
        PostVideoJob job = new PostVideoJob();
        job.id = id;
        job.userId = userId;
        job.content = content;
        job.qualityTier = qualityTier;
        job.inputPath = inputPath;
        job.inputExtension = inputExtension;
        job.thumbnailUrl = thumbnailUrl;
        job.status = PostVideoJobStatus.QUEUED;
        job.createdAt = now;
        return job;
    }

    public void start(OffsetDateTime now) {
        status = PostVideoJobStatus.PROCESSING;
        attemptCount++;
        startedAt = now;
    }

    // 본문은 게시물로 옮기거나(완료) 쓰지 않게 되면(실패) 지운다. 게시물을 영구 삭제한 뒤 사본이 남지 않게 한다.
    public void complete(UUID createdPostId, OffsetDateTime now) {
        status = PostVideoJobStatus.COMPLETED;
        postId = createdPostId;
        content = null;
        errorMessage = null;
        finishedAt = now;
    }

    public void fail(String message, OffsetDateTime now) {
        status = PostVideoJobStatus.FAILED;
        content = null;
        errorMessage = message;
        finishedAt = now;
    }

    /** 처리 중에 서버가 멈춘 작업을 다시 대기열에 넣는다. */
    public void requeue() {
        status = PostVideoJobStatus.QUEUED;
        startedAt = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getContent() {
        return content;
    }

    public VideoQualityTier getQualityTier() {
        return qualityTier;
    }

    public String getInputPath() {
        return inputPath;
    }

    public String getInputExtension() {
        return inputExtension;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public PostVideoJobStatus getStatus() {
        return status;
    }

    public UUID getPostId() {
        return postId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
