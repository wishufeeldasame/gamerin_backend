package com.gamerin.backend.domain.auth.dto.response;

public record HandleAvailabilityResponse(
        String handle,
        boolean available
) {
}
