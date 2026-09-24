package com.gamerin.backend.domain.hashtag.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "hashtags")
public class Hashtag {

    @Id
    private UUID id;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(name = "normalized_name", nullable = false, unique = true, length = 150)
    private String normalizedName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Hashtag() {
    }

    public UUID getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
