package com.gamerin.backend.domain.bookmark.dto.response;

import java.util.List;
import java.util.UUID;

public record BookmarkMembershipResponse(
        UUID postId,
        boolean bookmarkedByMe,
        List<UUID> collectionIds,
        BookmarkCollectionResponse collection
) {
}
