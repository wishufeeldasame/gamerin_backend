package com.gamerin.backend.domain.post.entity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gamerin.backend.domain.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "posts")
public class Post {

    /** KP-C3 (#92): 게시물당 허용되는 최대 수정 횟수 (1회) */
    public static final int MAX_EDIT_COUNT = 1;

    /** KP-C3 (#92): 최초 게시 후 수정 가능 시간 (1시간) */
    public static final Duration EDITABLE_DURATION = Duration.ofHours(1);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id")
    private User author;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "like_count", nullable = false)
    private long likeCount;

    @Column(name = "comment_count", nullable = false)
    private long commentCount;

    @Column(name = "share_count", nullable = false)
    private long shareCount;

    /** KP-C3 (#92): 게시물 수정 횟수 (0~1, SMALLINT) */
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "edit_count", nullable = false)
    private int editCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected Post() {
    }

    public static Post create(User author, String content) {
        Post post = new Post();
        post.author = author;
        post.content = content;
        post.likeCount = 0L;
        post.commentCount = 0L;
        post.shareCount = 0L;
        post.editCount = 0;
        return post;
    }

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public void increaseLikeCount() {
        this.likeCount++;
    }

    public void decreaseLikeCount() {
        if (this.likeCount > 0) {
            this.likeCount--;
        }
    }

    public void increaseCommentCount() {
        this.commentCount++;
    }

    public void decreaseCommentCount() {
        if (this.commentCount > 0) {
            this.commentCount--;
        }
    }

    public void increaseShareCount() {
        this.shareCount++;
    }

    /**
     * KP-C3 (#92): 게시물 본문을 수정하고 수정 횟수를 1 증가시킨다.
     */
    public void editContent(String content) {
        if (this.editCount >= MAX_EDIT_COUNT) {
            throw new IllegalStateException("Post edit count exceeded.");
        }
        this.content = content;
        this.editCount += 1;
    }

    /**
     * KP-C3 (#92): 최초 게시 시각(createdAt) 기준 수정 가능 마감 시각(createdAt +
     * 1시간)을 반환한다.
     */
    public OffsetDateTime editableUntil() {
        return createdAt != null ? createdAt.plus(EDITABLE_DURATION) : null;
    }

    public void softDelete() {
        this.deletedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public User getAuthor() {
        return author;
    }

    public String getContent() {
        return content;
    }

    public long getLikeCount() {
        return likeCount;
    }

    public long getCommentCount() {
        return commentCount;
    }

    public long getShareCount() {
        return shareCount;
    }

    public int getEditCount() {
        return editCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void restore() {
        this.deletedAt = null;
    }
}