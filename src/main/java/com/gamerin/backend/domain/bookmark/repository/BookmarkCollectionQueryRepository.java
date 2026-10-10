package com.gamerin.backend.domain.bookmark.repository;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.bookmark.entity.BookmarkCollectionItem;
import com.gamerin.backend.domain.post.entity.PostBookmark;
import com.gamerin.backend.domain.post.repository.PostBookmarkRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@Repository
public class BookmarkCollectionQueryRepository {

    private final EntityManager entityManager;
    private final BookmarkCollectionItemRepository itemRepository;
    private final PostBookmarkRepository postBookmarkRepository;

    public BookmarkCollectionQueryRepository(
            EntityManager entityManager,
            BookmarkCollectionItemRepository itemRepository,
            PostBookmarkRepository postBookmarkRepository
    ) {
        this.entityManager = entityManager;
        this.itemRepository = itemRepository;
        this.postBookmarkRepository = postBookmarkRepository;
    }

    public Map<UUID, CollectionMetrics> findCollectionMetrics(UUID userId, UUID postId) {
        String containsPostSql = postId == null
                ? "false"
                : """
                    exists (
                        select 1
                        from bookmark_collection_items selected_item
                        join post_bookmarks selected_bookmark
                          on selected_bookmark.id = selected_item.post_bookmark_id
                        where selected_item.collection_id = bc.id
                          and selected_bookmark.user_id = :userId
                          and selected_bookmark.post_id = :postId
                          and exists (
                              select 1
                              from posts selected_post
                              join users selected_author on selected_author.id = selected_post.author_id
                              where selected_post.id = selected_bookmark.post_id
                                and selected_post.deleted_at is null
                                and selected_author.deleted_at is null
                          )
                    )
                    """;

        String sql = """
            select
                bc.id,
                coalesce(sum(case
                    when item.id is not null
                     and bookmark.user_id = bc.user_id
                     and post.deleted_at is null
                     and author.deleted_at is null
                    then 1 else 0 end), 0) as bookmark_count,
                %s as contains_post,
                (
                    select case
                        when media.media_type = 'IMAGE' then media.media_url
                        else media.thumbnail_url
                    end
                    from bookmark_collection_items cover_item
                    join post_bookmarks cover_bookmark
                      on cover_bookmark.id = cover_item.post_bookmark_id
                    join posts cover_post on cover_post.id = cover_bookmark.post_id
                    join users cover_author on cover_author.id = cover_post.author_id
                    join post_media media on media.post_id = cover_post.id
                    where cover_item.collection_id = bc.id
                      and cover_bookmark.user_id = bc.user_id
                      and cover_post.deleted_at is null
                      and cover_author.deleted_at is null
                      and media.deleted_at is null
                      and (
                          (media.media_type = 'IMAGE' and media.media_url is not null)
                          or (media.media_type = 'VIDEO' and media.thumbnail_url is not null)
                      )
                    order by cover_item.added_at desc,
                             cover_item.id desc,
                             media.sort_order asc,
                             media.id asc
                    limit 1
                ) as cover_image_url
            from bookmark_collections bc
            left join bookmark_collection_items item on item.collection_id = bc.id
            left join post_bookmarks bookmark on bookmark.id = item.post_bookmark_id
            left join posts post on post.id = bookmark.post_id
            left join users author on author.id = post.author_id
            where bc.user_id = :userId
            group by bc.id
            """.formatted(containsPostSql);

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("userId", userId);
        if (postId != null) {
            query.setParameter("postId", postId);
        }

        Map<UUID, CollectionMetrics> metrics = new HashMap<>();
        for (Object rawRow : query.getResultList()) {
            Object[] row = (Object[]) rawRow;
            UUID collectionId = toUuid(row[0]);
            long count = ((Number) row[1]).longValue();
            boolean containsPost = toBoolean(row[2]);
            String coverImageUrl = row[3] == null ? null : String.valueOf(row[3]);
            metrics.put(collectionId, new CollectionMetrics(count, coverImageUrl, containsPost));
        }
        return metrics;
    }

    public List<BookmarkCollectionItem> findCollectionItems(
            UUID userId,
            UUID collectionId,
            String cursor,
            int limit,
            String keyword,
            boolean mediaOnly
    ) {
        ItemCursor parsedCursor = ItemCursor.parse(cursor);
        StringBuilder sql = new StringBuilder("""
            select item.id
            from bookmark_collection_items item
            join bookmark_collections collection on collection.id = item.collection_id
            join post_bookmarks bookmark on bookmark.id = item.post_bookmark_id
            join posts post on post.id = bookmark.post_id
            join users author on author.id = post.author_id
            where collection.id = :collectionId
              and collection.user_id = :userId
              and bookmark.user_id = :userId
              and post.deleted_at is null
              and author.deleted_at is null
            """);

        appendPostFilters(sql, keyword, mediaOnly);
        appendItemCursor(sql, parsedCursor);
        sql.append(" order by item.added_at desc, item.id desc limit :limit");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("collectionId", collectionId);
        query.setParameter("userId", userId);
        bindPostFilters(query, keyword);
        bindItemCursor(query, parsedCursor);
        query.setParameter("limit", limit);

        return reorderItems(toUuidList(query.getResultList()));
    }

    public List<PostBookmark> findUnclassifiedBookmarks(
            UUID userId,
            String cursor,
            int limit,
            String keyword,
            boolean mediaOnly
    ) {
        BookmarkCursor parsedCursor = BookmarkCursor.parse(cursor);
        StringBuilder sql = new StringBuilder("""
            select bookmark.id
            from post_bookmarks bookmark
            join posts post on post.id = bookmark.post_id
            join users author on author.id = post.author_id
            where bookmark.user_id = :userId
              and post.deleted_at is null
              and author.deleted_at is null
              and not exists (
                  select 1
                  from bookmark_collection_items item
                  where item.post_bookmark_id = bookmark.id
              )
            """);

        appendPostFilters(sql, keyword, mediaOnly);
        appendBookmarkCursor(sql, parsedCursor);
        sql.append(" order by bookmark.created_at desc, bookmark.id desc limit :limit");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("userId", userId);
        bindPostFilters(query, keyword);
        bindBookmarkCursor(query, parsedCursor);
        query.setParameter("limit", limit);

        return reorderBookmarks(toUuidList(query.getResultList()));
    }

    private void appendPostFilters(StringBuilder sql, String keyword, boolean mediaOnly) {
        if (keyword != null && !keyword.isBlank()) {
            sql.append("""
                and (
                    lower(coalesce(post.content, '')) like :keyword escape '\\'
                    or lower(author.nickname) like :keyword escape '\\'
                    or lower(author.handle) like :keyword escape '\\'
                )
                """);
        }
        if (mediaOnly) {
            sql.append("""
                and exists (
                    select 1
                    from post_media media_filter
                    where media_filter.post_id = post.id
                      and media_filter.deleted_at is null
                )
                """);
        }
    }

    private void bindPostFilters(Query query, String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            query.setParameter(
                    "keyword",
                    "%" + escapeLike(keyword.strip().toLowerCase(Locale.ROOT)) + "%"
            );
        }
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private void appendItemCursor(StringBuilder sql, ItemCursor cursor) {
        if (cursor == null) {
            return;
        }
        sql.append("""
            and (
                item.added_at < :cursorAddedAt
                or (item.added_at = :cursorAddedAt and item.id < :cursorItemId)
            )
            """);
    }

    private void bindItemCursor(Query query, ItemCursor cursor) {
        if (cursor == null) {
            return;
        }
        query.setParameter("cursorAddedAt", Timestamp.from(cursor.addedAt().toInstant()));
        query.setParameter("cursorItemId", cursor.itemId());
    }

    private void appendBookmarkCursor(StringBuilder sql, BookmarkCursor cursor) {
        if (cursor == null) {
            return;
        }
        sql.append("""
            and (
                bookmark.created_at < :cursorCreatedAt
                or (bookmark.created_at = :cursorCreatedAt and bookmark.id < :cursorBookmarkId)
            )
            """);
    }

    private void bindBookmarkCursor(Query query, BookmarkCursor cursor) {
        if (cursor == null) {
            return;
        }
        query.setParameter("cursorCreatedAt", Timestamp.from(cursor.createdAt().toInstant()));
        query.setParameter("cursorBookmarkId", cursor.bookmarkId());
    }

    private List<BookmarkCollectionItem> reorderItems(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Integer> order = buildOrder(ids);
        List<BookmarkCollectionItem> items = new ArrayList<>(
                itemRepository.findAllByIdsWithBookmarkAndPost(ids)
        );
        items.sort((left, right) -> Integer.compare(order.get(left.getId()), order.get(right.getId())));
        return items;
    }

    private List<PostBookmark> reorderBookmarks(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Integer> order = buildOrder(ids);
        List<PostBookmark> bookmarks = new ArrayList<>(
                postBookmarkRepository.findAllByIdsWithPostAuthor(ids)
        );
        bookmarks.sort((left, right) -> Integer.compare(order.get(left.getId()), order.get(right.getId())));
        return bookmarks;
    }

    private Map<UUID, Integer> buildOrder(List<UUID> ids) {
        Map<UUID, Integer> order = new HashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            order.put(ids.get(index), index);
        }
        return order;
    }

    private List<UUID> toUuidList(List<?> rows) {
        return rows.stream().map(this::toUuid).toList();
    }

    private UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof byte[] bytes && bytes.length == 16) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
        return UUID.fromString(String.valueOf(value));
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효하지 않은 북마크 커서입니다.");
    }

    public record CollectionMetrics(long bookmarkCount, String coverImageUrl, boolean containsPost) {
        public static CollectionMetrics empty() {
            return new CollectionMetrics(0L, null, false);
        }
    }

    private record ItemCursor(OffsetDateTime addedAt, UUID itemId) {
        private static ItemCursor parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String[] values = raw.split("\\|", -1);
            if (values.length != 2) {
                throw invalidCursor();
            }
            try {
                return new ItemCursor(OffsetDateTime.parse(values[0]), UUID.fromString(values[1]));
            } catch (DateTimeParseException | IllegalArgumentException ex) {
                throw invalidCursor();
            }
        }
    }

    private record BookmarkCursor(OffsetDateTime createdAt, UUID bookmarkId) {
        private static BookmarkCursor parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String[] values = raw.split("\\|", -1);
            if (values.length != 2) {
                throw invalidCursor();
            }
            try {
                return new BookmarkCursor(OffsetDateTime.parse(values[0]), UUID.fromString(values[1]));
            } catch (DateTimeParseException | IllegalArgumentException ex) {
                throw invalidCursor();
            }
        }
    }
}
