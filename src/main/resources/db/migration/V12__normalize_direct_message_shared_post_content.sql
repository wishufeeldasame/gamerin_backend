UPDATE direct_messages
SET content = ''
WHERE content IS NULL
  AND shared_post_id IS NOT NULL;
