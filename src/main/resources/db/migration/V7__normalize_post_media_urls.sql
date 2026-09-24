UPDATE post_media
SET media_url = regexp_replace(media_url, '^https?://[^/]+/uploads/', '/uploads/'),
    thumbnail_url = CASE
        WHEN thumbnail_url IS NULL THEN NULL
        ELSE regexp_replace(thumbnail_url, '^https?://[^/]+/uploads/', '/uploads/')
    END
WHERE media_url ~ '^https?://[^/]+/uploads/'
   OR thumbnail_url ~ '^https?://[^/]+/uploads/';
