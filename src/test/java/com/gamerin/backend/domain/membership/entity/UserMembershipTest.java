package com.gamerin.backend.domain.membership.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.OffsetDateTime;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.gamerin.backend.domain.user.entity.User;

class UserMembershipTest {

    @ParameterizedTest
    @CsvSource({
            "2026-01-31T00:00:00Z, 2026-03-02T00:00:00Z",
            "2028-02-01T09:00:00+09:00, 2028-03-02T09:00:00+09:00",
            "2126-12-20T00:00:00Z, 2127-01-19T00:00:00Z"
    })
    void appliesThirtyDaysFromTheCallerSelectedStart(String start, String expectedExpiry) {
        UserMembership membership = UserMembership.create(mock(User.class),
                OffsetDateTime.parse("2000-01-01T00:00:00Z"));

        membership.applyMembershipPassFrom(OffsetDateTime.parse(start));

        assertThat(membership.getExpiresAt()).isEqualTo(OffsetDateTime.parse(expectedExpiry));
    }
}
