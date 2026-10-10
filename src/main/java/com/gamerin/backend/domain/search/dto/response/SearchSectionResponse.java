package com.gamerin.backend.domain.search.dto.response;

import java.util.List;

public record SearchSectionResponse<T>(
        List<T> items,
        boolean hasMore
) {
}
