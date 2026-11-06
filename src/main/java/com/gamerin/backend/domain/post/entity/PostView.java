package com.gamerin.backend.domain.post.entity;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** (post_id, user_id)당 마지막으로 조회수에 반영된 시각. 읽기·쓰기는 PostViewRepository의 SQL로만 한다(#183). */
@Entity
@Table(name = "post_views")
@IdClass(PostView.Key.class)
public class PostView {

    @Id
    @Column(name = "post_id")
    private UUID postId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "viewed_at", nullable = false)
    private OffsetDateTime viewedAt;

    protected PostView() {
    }

    public UUID getPostId() {
        return postId;
    }

    public UUID getUserId() {
        return userId;
    }

    public OffsetDateTime getViewedAt() {
        return viewedAt;
    }

    public record Key(UUID postId, UUID userId) implements Serializable {
    }
}
