-- 게시글 URL용 숫자 번호(#172). 내부 PK·FK는 UUID를 유지하고 URL·조회 API에서만 사용한다.
ALTER TABLE posts ADD COLUMN post_number BIGINT;

-- 기존 게시글은 작성 순(created_at, id)으로 1부터 번호를 부여한다.
UPDATE posts p
SET post_number = r.rn
FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY created_at, id) AS rn FROM posts) r
WHERE p.id = r.id;

CREATE SEQUENCE posts_post_number_seq OWNED BY posts.post_number;
SELECT setval('posts_post_number_seq', COALESCE((SELECT MAX(post_number) FROM posts), 0) + 1, false);

ALTER TABLE posts ALTER COLUMN post_number SET DEFAULT nextval('posts_post_number_seq');
ALTER TABLE posts ALTER COLUMN post_number SET NOT NULL;
ALTER TABLE posts ADD CONSTRAINT uq_posts_post_number UNIQUE (post_number);
