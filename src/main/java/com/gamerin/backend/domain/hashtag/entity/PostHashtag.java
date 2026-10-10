package com.gamerin.backend.domain.hashtag.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.post.entity.Post;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "post_hashtags")
public class PostHashtag {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hashtag_id", nullable = false)
    private Hashtag hashtag;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PostHashtag() {
    }

    public UUID getId() {
        return id;
    }

    public Post getPost() {
        return post;
    }

    public Hashtag getHashtag() {
        return hashtag;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
