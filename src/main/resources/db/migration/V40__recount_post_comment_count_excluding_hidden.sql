-- 신고로 숨긴 댓글(deleted_at 설정)이 댓글 수에서 빠지지 않던 기존 데이터를 보정한다(#78).
UPDATE posts p
SET comment_count = (
    SELECT COUNT(*)
    FROM post_comments c
    WHERE c.post_id = p.id
      AND c.deleted_at IS NULL
)
WHERE p.comment_count <> (
    SELECT COUNT(*)
    FROM post_comments c
    WHERE c.post_id = p.id
      AND c.deleted_at IS NULL
);
