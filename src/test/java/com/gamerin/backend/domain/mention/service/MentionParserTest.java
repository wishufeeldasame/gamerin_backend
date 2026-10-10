package com.gamerin.backend.domain.mention.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.gamerin.backend.domain.mention.model.ParsedMention;

class MentionParserTest {

    private final MentionParser parser = new MentionParser();

    @Test
    void parsesHandlesUsingExistingLowercaseHandlePolicy() {
        assertThat(parser.parse("hello @alpha @user.name @squad_1"))
                .containsExactly(
                        new ParsedMention("alpha", java.util.List.of("alpha")),
                        new ParsedMention("user.name", java.util.List.of("user.name")),
                        new ParsedMention("squad_1", java.util.List.of("squad_1"))
                );
    }

    @Test
    void keepsExactDotHandleFirstAndProvidesTrailingPunctuationFallbacks() {
        assertThat(parser.parse("@player. @player..."))
                .containsExactly(
                        new ParsedMention("player.", java.util.List.of("player.", "player")),
                        new ParsedMention(
                                "player...",
                                java.util.List.of("player...", "player..", "player.", "player")
                        )
                );
    }

    @Test
    void acceptsSafePunctuationBoundariesAndNewlines() {
        assertThat(parser.parse("(@alpha),\n\"@bravo\" !@charlie"))
                .extracting(ParsedMention::rawHandle)
                .containsExactly("alpha", "bravo", "charlie");
    }

    @Test
    void rejectsEmailsUrlsAdjacentTextAndInvalidCaseWithoutPartialMatches() {
        String content = "mail@example.com https://example.com/@alpha foo@bravo "
                + "@Charlie @delta\uD55C\uAE00 @ab @" + "x".repeat(21);

        assertThat(parser.parse(content)).isEmpty();
    }

    @Test
    void ignoresEmptyDoubleAtHashAdjacentAndBlankContent() {
        assertThat(parser.parse("@ @@alpha #@bravo")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
    }
}
