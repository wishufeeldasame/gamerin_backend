package com.gamerin.backend.domain.bookmark.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.post.entity.PostBookmark;

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
        name = "bookmark_collection_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_bookmark_collection_item",
                columnNames = {"collection_id", "post_bookmark_id"}
        )
)
public class BookmarkCollectionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "collection_id", nullable = false)
    private BookmarkCollection collection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_bookmark_id", nullable = false)
    private PostBookmark postBookmark;

    @Column(name = "added_at", nullable = false, updatable = false)
    private OffsetDateTime addedAt;

    protected BookmarkCollectionItem() {
    }

    public static BookmarkCollectionItem create(
            BookmarkCollection collection,
            PostBookmark postBookmark
    ) {
        BookmarkCollectionItem item = new BookmarkCollectionItem();
        item.collection = collection;
        item.postBookmark = postBookmark;
        return item;
    }

    @PrePersist
    protected void onCreate() {
        this.addedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public BookmarkCollection getCollection() {
        return collection;
    }

    public PostBookmark getPostBookmark() {
        return postBookmark;
    }

    public OffsetDateTime getAddedAt() {
        return addedAt;
    }
}
