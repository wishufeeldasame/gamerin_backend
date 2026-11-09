package com.gamerin.backend.domain.upload.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.post.entity.PostVideoJobStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "upload_jobs", uniqueConstraints = @UniqueConstraint(name = "uk_upload_jobs_owner_kind_key", columnNames = {"user_id", "kind", "client_key"}))
public class UploadJob {
    public enum Kind { POST, MESSAGE }

    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Kind kind;
    @Column(name = "client_key", nullable = false) private UUID clientKey;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Column(columnDefinition = "TEXT") private String payload;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PostVideoJobStatus status;
    @Column(name = "result_id") private UUID resultId;
    @Column(name = "error_code", length = 40) private String errorCode;
    @Column(name = "error_message", columnDefinition = "TEXT") private String errorMessage;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @Column(name = "finished_at") private OffsetDateTime finishedAt;

    protected UploadJob() {}

    public static UploadJob queue(UUID id, UUID userId, Kind kind, UUID clientKey, String fingerprint, String payload) {
        UploadJob job = new UploadJob();
        job.id = id;
        job.userId = userId;
        job.kind = kind;
        job.clientKey = clientKey;
        job.fingerprint = fingerprint;
        job.payload = payload;
        job.status = PostVideoJobStatus.QUEUED;
        job.createdAt = OffsetDateTime.now();
        return job;
    }

    public void start() { status = PostVideoJobStatus.PROCESSING; attemptCount++; }
    public void requeue() { status = PostVideoJobStatus.QUEUED; }
    public void complete(UUID resultId) {
        this.resultId = resultId;
        status = PostVideoJobStatus.COMPLETED;
        finishedAt = OffsetDateTime.now();
    }
    public void fail(String code, String message) {
        status = PostVideoJobStatus.FAILED;
        errorCode = code;
        errorMessage = message;
        finishedAt = OffsetDateTime.now();
    }
    public void clearPayload() { payload = null; }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public Kind getKind() { return kind; }
    public UUID getClientKey() { return clientKey; }
    public String getFingerprint() { return fingerprint; }
    public String getPayload() { return payload; }
    public PostVideoJobStatus getStatus() { return status; }
    public UUID getResultId() { return resultId; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public int getAttemptCount() { return attemptCount; }
}
