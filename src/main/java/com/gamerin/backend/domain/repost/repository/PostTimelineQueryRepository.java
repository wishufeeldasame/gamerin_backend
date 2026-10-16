package com.gamerin.backend.domain.repost.repository;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.repost.dto.response.ReposterInfoResponse;
import com.gamerin.backend.domain.repost.model.PostTimelineItem;
import com.gamerin.backend.domain.repost.model.TimelineCursor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@Repository
public class PostTimelineQueryRepository {

    private final EntityManager entityManager;
    private final PostRepository postRepository;

    public PostTimelineQueryRepository(EntityManager entityManager, PostRepository postRepository) {
        this.entityManager = entityManager;
        this.postRepository = postRepository;
    }

    public List<PostTimelineItem> findFeedItems(
            UUID viewerId,
            boolean followingOnly,
            TimelineCursor cursor,
            int limit
    ) {
        Scope scope = followingOnly ? Scope.FOLLOWING : Scope.ALL;
        return findTimelineItems(scope, viewerId, cursor, limit);
    }

    public List<PostTimelineItem> findUserItems(
            UUID targetUserId,
            TimelineCursor cursor,
            int limit
    ) {
        return findTimelineItems(Scope.USER, targetUserId, cursor, limit);
    }

    private List<PostTimelineItem> findTimelineItems(
            Scope scope,
            UUID actorId,
            TimelineCursor cursor,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
            with activities as (
                select post.id as post_id,
                       post.author_id as actor_id,
                       post.created_at as activity_at,
                       post.id as activity_id,
                       cast(null as uuid) as reposter_id,
                       cast(null as timestamp with time zone) as reposted_at
                from posts post
                join users original_author on original_author.id = post.author_id
                where post.deleted_at is null
                  and original_author.deleted_at is null

                union all

                select post.id as post_id,
                       repost.user_id as actor_id,
                       repost.reposted_at as activity_at,
                       repost.id as activity_id,
                       repost.user_id as reposter_id,
                       repost.reposted_at as reposted_at
                from post_reposts repost
                join posts post on post.id = repost.post_id
                join users original_author on original_author.id = post.author_id
                join users repost_user on repost_user.id = repost.user_id
                where post.deleted_at is null
                  and original_author.deleted_at is null
                  and repost_user.deleted_at is null
            ),
            eligible as (
                select activity.*
                from activities activity
                where activity.activity_at <= :snapshotAt
            """);

        appendScopeFilter(sql, scope);
        sql.append("""
            ),
            ranked as (
                select eligible.*,
                       row_number() over (
                           partition by eligible.post_id
                           order by eligible.activity_at desc, eligible.activity_id desc
                       ) as timeline_rank
                from eligible
            )
            select ranked.post_id,
                   ranked.reposter_id,
                   reposter.nickname,
                   ranked.reposted_at,
                   ranked.activity_at
            from ranked
            left join users reposter on reposter.id = ranked.reposter_id
            where ranked.timeline_rank = 1
            """);

        appendCursorFilter(sql, cursor);
        sql.append(" order by ranked.activity_at desc, ranked.post_id desc limit :limit");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("snapshotAt", Timestamp.from(cursor.snapshotAt().toInstant()));
        bindScope(query, scope, actorId);
        bindCursor(query, cursor);
        query.setParameter("limit", limit);

        return mapTimelineItems(query.getResultList());
    }

    private void appendScopeFilter(StringBuilder sql, Scope scope) {
        if (scope == Scope.FOLLOWING) {
            sql.append("""
                  and (
                      activity.actor_id = :viewerId
                      or exists (
                          select 1
                          from follows follow_relation
                          where follow_relation.follower_id = :viewerId
                            and follow_relation.followee_id = activity.actor_id
                      )
                  )
                """);
        } else if (scope == Scope.USER) {
            sql.append(" and activity.actor_id = :targetUserId");
        }
    }

    private void bindScope(Query query, Scope scope, UUID actorId) {
        if (scope == Scope.FOLLOWING) {
            query.setParameter("viewerId", actorId);
        } else if (scope == Scope.USER) {
            query.setParameter("targetUserId", actorId);
        }
    }

    private void appendCursorFilter(StringBuilder sql, TimelineCursor cursor) {
        if (!cursor.hasPosition()) {
            return;
        }
        sql.append("""
              and (
                  ranked.activity_at < :cursorActivityAt
                  or (
                      ranked.activity_at = :cursorActivityAt
                      and ranked.post_id < :cursorPostId
                  )
              )
            """);
    }

    private void bindCursor(Query query, TimelineCursor cursor) {
        if (!cursor.hasPosition()) {
            return;
        }
        query.setParameter("cursorActivityAt", Timestamp.from(cursor.activityAt().toInstant()));
        query.setParameter("cursorPostId", cursor.postId());
    }

    private List<PostTimelineItem> mapTimelineItems(List<?> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<TimelineRow> timelineRows = rows.stream()
                .map(this::toTimelineRow)
                .toList();
        Map<UUID, Post> posts = indexPosts(postRepository.findAllByIds(
                timelineRows.stream().map(TimelineRow::postId).toList()
        ));

        return timelineRows.stream()
                .filter(row -> posts.containsKey(row.postId()))
                .map(row -> new PostTimelineItem(
                        posts.get(row.postId()),
                        row.reposterId() == null
                                ? null
                                : new ReposterInfoResponse(
                                        row.reposterId(),
                                        row.reposterNickname(),
                                        row.repostedAt()
                                ),
                        row.activityAt()
                ))
                .toList();
    }

    private TimelineRow toTimelineRow(Object value) {
        Object[] row = (Object[]) value;
        return new TimelineRow(
                toUuid(row[0]),
                row[1] == null ? null : toUuid(row[1]),
                row[2] == null ? null : String.valueOf(row[2]),
                row[3] == null ? null : toOffsetDateTime(row[3]),
                toOffsetDateTime(row[4])
        );
    }

    private Map<UUID, Post> indexPosts(Collection<Post> posts) {
        Map<UUID, Post> indexed = new HashMap<>();
        for (Post post : posts) {
            indexed.put(post.getId(), post);
        }
        return indexed;
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

    private OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        }
        if (value instanceof Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atOffset(ZoneOffset.UTC);
        }
        return OffsetDateTime.parse(String.valueOf(value));
    }

    private enum Scope {
        ALL,
        FOLLOWING,
        USER
    }

    private record TimelineRow(
            UUID postId,
            UUID reposterId,
            String reposterNickname,
            OffsetDateTime repostedAt,
            OffsetDateTime activityAt
    ) {
    }
}
