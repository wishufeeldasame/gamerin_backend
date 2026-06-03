package com.gamerin.backend.domain.post.repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostBookmark;
import com.gamerin.backend.domain.post.entity.PostMedia;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@Repository
public class PostQueryRepositoryImpl implements PostQueryRepository {

    private final EntityManager entityManager;
    private final PostRepository postRepository;
    private final PostMediaRepository postMediaRepository;
    private final PostBookmarkRepository postBookmarkRepository;

    public PostQueryRepositoryImpl(
            EntityManager entityManager,
            PostRepository postRepository,
            PostMediaRepository postMediaRepository,
            PostBookmarkRepository postBookmarkRepository
    ) {
        this.entityManager = entityManager;
        this.postRepository = postRepository;
        this.postMediaRepository = postMediaRepository;
        this.postBookmarkRepository = postBookmarkRepository;
    }

    @Override
    public List<Post> findFeedPosts(UUID viewerId, boolean followingOnly, String cursor, int limit) {
        PostCursor postCursor = PostCursor.parse(cursor);
        StringBuilder sql = new StringBuilder("""
            select p.id
            from posts p
            join users u on u.id = p.author_id
            where p.deleted_at is null
              and u.deleted_at is null
            """);

        if (followingOnly) {
            sql.append("""
                and (
                    p.author_id = :viewerId
                    or exists (
                        select 1
                        from follows f
                        where f.follower_id = :viewerId
                          and f.followee_id = p.author_id
                    )
                )
                """);
        }

        appendPostCursor(sql, postCursor);
        sql.append(" order by p.created_at desc, p.id desc limit :limit");

        Query query = entityManager.createNativeQuery(sql.toString());
        if (followingOnly) {
            query.setParameter("viewerId", viewerId);
        }
        bindPostCursor(query, postCursor);
        query.setParameter("limit", limit);

        return reorderPosts(castUuidList(query.getResultList()));
    }

    @Override
    public List<Post> findUserPosts(String handle, String cursor, int limit) {
        PostCursor postCursor = PostCursor.parse(cursor);
        StringBuilder sql = new StringBuilder("""
            select p.id
            from posts p
            join users u on u.id = p.author_id
            where p.deleted_at is null
              and u.deleted_at is null
              and u.handle = :handle
            """);

        appendPostCursor(sql, postCursor);
        sql.append(" order by p.created_at desc, p.id desc limit :limit");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("handle", handle);
        bindPostCursor(query, postCursor);
        query.setParameter("limit", limit);

        return reorderPosts(castUuidList(query.getResultList()));
    }

    @Override
    public List<PostMedia> findUserMedia(String handle, String cursor, int limit) {
        MediaCursor mediaCursor = MediaCursor.parse(cursor);
        StringBuilder sql = new StringBuilder("""
            select pm.id
            from post_media pm
            join posts p on p.id = pm.post_id
            join users u on u.id = p.author_id
            where p.deleted_at is null
              and pm.deleted_at is null
              and u.deleted_at is null
              and u.handle = :handle
            """);

        appendMediaCursor(sql, mediaCursor);
        sql.append("""
             order by p.created_at desc, p.id desc, pm.sort_order asc, pm.id asc
             limit :limit
            """);

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("handle", handle);
        bindMediaCursor(query, mediaCursor);
        query.setParameter("limit", limit);

        return reorderMedia(castUuidList(query.getResultList()));
    }

    @Override
    public List<PostBookmark> findBookmarkedPosts(UUID userId, String cursor, int limit) {
        BookmarkCursor bookmarkCursor = BookmarkCursor.parse(cursor);
        StringBuilder sql = new StringBuilder("""
            select pb.id
            from post_bookmarks pb
            join posts p on p.id = pb.post_id
            where pb.user_id = :userId
              and p.deleted_at is null
            """);

        appendBookmarkCursor(sql, bookmarkCursor);
        sql.append(" order by pb.created_at desc, pb.id desc limit :limit");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("userId", userId);
        bindBookmarkCursor(query, bookmarkCursor);
        query.setParameter("limit", limit);

        return reorderBookmarks(castUuidList(query.getResultList()));
    }

    private void appendPostCursor(StringBuilder sql, PostCursor cursor) {
        if (cursor == null) {
            return;
        }

        sql.append("""
            and (
                p.created_at < :cursorCreatedAt
                or (p.created_at = :cursorCreatedAt and p.id < :cursorId)
            )
            """);
    }

    private void bindPostCursor(Query query, PostCursor cursor) {
        if (cursor == null) {
            return;
        }
        query.setParameter("cursorCreatedAt", Timestamp.from(cursor.createdAt().toInstant()));
        query.setParameter("cursorId", cursor.postId());
    }

    private void appendMediaCursor(StringBuilder sql, MediaCursor cursor) {
        if (cursor == null) {
            return;
        }

        sql.append("""
            and (
                p.created_at < :cursorCreatedAt
                or (p.created_at = :cursorCreatedAt and p.id < :cursorPostId)
                or (p.created_at = :cursorCreatedAt and p.id = :cursorPostId and pm.sort_order > :cursorSortOrder)
                or (p.created_at = :cursorCreatedAt and p.id = :cursorPostId and pm.sort_order = :cursorSortOrder and pm.id > :cursorMediaId)
            )
            """);
    }

    private void bindMediaCursor(Query query, MediaCursor cursor) {
        if (cursor == null) {
            return;
        }
        query.setParameter("cursorCreatedAt", Timestamp.from(cursor.createdAt().toInstant()));
        query.setParameter("cursorPostId", cursor.postId());
        query.setParameter("cursorSortOrder", cursor.sortOrder());
        query.setParameter("cursorMediaId", cursor.mediaId());
    }

    private void appendBookmarkCursor(StringBuilder sql, BookmarkCursor cursor) {
        if (cursor == null) {
            return;
        }

        sql.append("""
            and (
                pb.created_at < :cursorCreatedAt
                or (pb.created_at = :cursorCreatedAt and pb.id < :cursorBookmarkId)
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

    private List<Post> reorderPosts(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<UUID, Integer> order = new HashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            order.put(ids.get(index), index);
        }

        List<Post> posts = new ArrayList<>(postRepository.findAllById(ids));
        posts.sort((left, right) -> Integer.compare(order.get(left.getId()), order.get(right.getId())));
        return posts;
    }

    private List<PostMedia> reorderMedia(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<UUID, Integer> order = new HashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            order.put(ids.get(index), index);
        }

        List<PostMedia> media = new ArrayList<>(postMediaRepository.findAllById(ids));
        media.sort((left, right) -> Integer.compare(order.get(left.getId()), order.get(right.getId())));
        return media;
    }

    private List<PostBookmark> reorderBookmarks(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<UUID, Integer> order = new HashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            order.put(ids.get(index), index);
        }

        List<PostBookmark> bookmarks = new ArrayList<>(postBookmarkRepository.findAllById(ids));
        bookmarks.sort((left, right) -> Integer.compare(order.get(left.getId()), order.get(right.getId())));
        return bookmarks;
    }

    private List<UUID> castUuidList(List<?> rows) {
        return rows.stream()
                .map(value -> {
                    if (value instanceof UUID uuid) {
                        return uuid;
                    }
                    return UUID.fromString(String.valueOf(value));
                })
                .toList();
    }

    private record PostCursor(OffsetDateTime createdAt, UUID postId) {
        private static PostCursor parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }

            String[] values = raw.split("\\|");
            if (values.length != 2) {
                return null;
            }

            return new PostCursor(OffsetDateTime.parse(values[0]), UUID.fromString(values[1]));
        }
    }

    private record MediaCursor(OffsetDateTime createdAt, UUID postId, int sortOrder, UUID mediaId) {
        private static MediaCursor parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }

            String[] values = raw.split("\\|");
            if (values.length != 4) {
                return null;
            }

            return new MediaCursor(
                    OffsetDateTime.parse(values[0]),
                    UUID.fromString(values[1]),
                    Integer.parseInt(values[2]),
                    UUID.fromString(values[3])
            );
        }
    }

    private record BookmarkCursor(OffsetDateTime createdAt, UUID bookmarkId) {
        private static BookmarkCursor parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }

            String[] values = raw.split("\\|");
            if (values.length != 2) {
                return null;
            }

            return new BookmarkCursor(OffsetDateTime.parse(values[0]), UUID.fromString(values[1]));
        }
    }
}
