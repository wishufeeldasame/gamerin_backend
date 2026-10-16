package com.gamerin.backend.domain.search.dto.response;

import com.gamerin.backend.domain.hashtag.dto.response.HashtagSummaryResponse;
import com.gamerin.backend.domain.post.dto.response.PostCardResponse;
import com.gamerin.backend.domain.user.dto.response.SimpleUserProfileResponse;

public record SearchOverviewResponse(
        String query,
        SearchSectionResponse<SimpleUserProfileResponse> accounts,
        SearchSectionResponse<PostCardResponse> posts,
        SearchSectionResponse<HashtagSummaryResponse> hashtags
) {
}
