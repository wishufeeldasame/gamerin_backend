package com.gamerin.backend.domain.bookmark.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.bookmark.entity.BookmarkCollectionItem;

public interface BookmarkCollectionItemRepository extends JpaRepository<BookmarkCollectionItem, UUID> {

    boolean existsByCollectionIdAndPostBookmarkId(UUID collectionId, UUID postBookmarkId);

    Optional<BookmarkCollectionItem> findByCollectionIdAndPostBookmarkId(
            UUID collectionId,
            UUID postBookmarkId
    );

    @Modifying(flushAutomatically = true)
    @Query("delete from BookmarkCollectionItem item where item.collection.id = :collectionId")
    int deleteAllByCollectionId(@Param("collectionId") UUID collectionId);

    @Modifying(flushAutomatically = true)
    @Query("delete from BookmarkCollectionItem item where item.postBookmark.id = :postBookmarkId")
    int deleteAllByPostBookmarkId(@Param("postBookmarkId") UUID postBookmarkId);

    @Query("""
        select item.collection.id
        from BookmarkCollectionItem item
        where item.collection.user.id = :userId
          and item.postBookmark.post.id = :postId
          and item.collection.id in :collectionIds
        """)
    List<UUID> findCollectionIdsContainingPost(
            @Param("userId") UUID userId,
            @Param("postId") UUID postId,
            @Param("collectionIds") Collection<UUID> collectionIds
    );

    @Query("""
        select item.collection.id
        from BookmarkCollectionItem item
        where item.collection.user.id = :userId
          and item.postBookmark.user.id = :userId
          and item.postBookmark.post.id = :postId
        order by item.collection.id
        """)
    List<UUID> findCollectionIdsContainingPost(
            @Param("userId") UUID userId,
            @Param("postId") UUID postId
    );

    @Query("""
        select item
        from BookmarkCollectionItem item
        join fetch item.postBookmark bookmark
        join fetch bookmark.post post
        join fetch post.author author
        left join fetch author.profile
        where item.id in :ids
        """)
    List<BookmarkCollectionItem> findAllByIdsWithBookmarkAndPost(
            @Param("ids") Collection<UUID> ids
    );
}
