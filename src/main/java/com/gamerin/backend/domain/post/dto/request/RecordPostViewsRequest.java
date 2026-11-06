package com.gamerin.backend.domain.post.dto.request;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RecordPostViewsRequest(
        @NotEmpty @Size(max = 50) List<@NotNull UUID> postIds
) {
}
