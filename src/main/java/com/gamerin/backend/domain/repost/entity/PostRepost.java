package com.gamerin.backend.domain.repost.entity;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.gamerin.backend.domain.post.entity.Post;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "post_reposts",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_post_reposts_post_user",
                columnNames = {"post_id", "user_id"}
        )
)
public class PostRepost {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "reposted_at", nullable = false, updatable = false)
    private OffsetDateTime repostedAt;

    protected PostRepost() {
    }

    public static PostRepost create(Post post, User user) {
        PostRepost repost = new PostRepost();
        repost.post = post;
        repost.user = user;
        return repost;
    }

    @PrePersist
    protected void onCreate() {
        this.repostedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public Post getPost() {
        return post;
    }

    public User getUser() {
        return user;
    }

    public OffsetDateTime getRepostedAt() {
        return repostedAt;
    }
}
