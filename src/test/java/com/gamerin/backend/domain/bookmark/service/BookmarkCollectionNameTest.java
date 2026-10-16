package com.gamerin.backend.domain.bookmark.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class BookmarkCollectionNameTest {

    @Test
    void trimsUnicodeWhitespaceAndNormalizesCase() {
        BookmarkCollectionName name = BookmarkCollectionName.from("\u00A0 Game Clips\u3000");

        assertThat(name.displayName()).isEqualTo("Game Clips");
        assertThat(name.normalizedName()).isEqualTo("game clips");
    }

    @Test
    void preservesFortyCodePointsIncludingSupplementaryCharacters() {
        String raw = "\uD83C\uDFAE".repeat(40);

        BookmarkCollectionName name = BookmarkCollectionName.from(raw);

        assertThat(name.displayName().codePointCount(0, name.displayName().length())).isEqualTo(40);
    }

    @Test
    void rejectsBlankAndNamesLongerThanFortyCodePoints() {
        assertInvalid("\u00A0\u3000\t");
        assertInvalid("a".repeat(41));
    }

    @Test
    void canonicalUnicodeFormsHaveSameNormalizedName() {
        BookmarkCollectionName composed = BookmarkCollectionName.from("caf\u00E9");
        BookmarkCollectionName decomposed = BookmarkCollectionName.from("cafe\u0301");

        assertThat(decomposed.normalizedName()).isEqualTo(composed.normalizedName());
    }

    private void assertInvalid(String value) {
        assertThatThrownBy(() -> BookmarkCollectionName.from(value))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
