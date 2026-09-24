package com.gamerin.backend.domain.repost.model;

public record RepostMetrics(
        long repostCount,
        boolean repostedByViewer
) {
}
