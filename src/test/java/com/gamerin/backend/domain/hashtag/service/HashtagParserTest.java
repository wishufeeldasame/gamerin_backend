package com.gamerin.backend.domain.hashtag.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.gamerin.backend.domain.hashtag.model.ParsedHashtag;

class HashtagParserTest {

    private final HashtagParser parser = new HashtagParser();

    @Test
    void parsesKoreanLatinDigitsAndUnderscoresInAppearanceOrder() {
        assertThat(parser.parse("#배그 오늘은 #PUBG_2026 랭크"))
                .containsExactly(
                        new ParsedHashtag("배그", "배그"),
                        new ParsedHashtag("PUBG_2026", "PUBG_2026")
                );
    }

    @Test
    void keepsDifferentCaseButRemovesUnicodeEquivalentDuplicates() {
        List<ParsedHashtag> hashtags = parser.parse("#PUBG #pubg #Cafe\u0301 #Café");

        assertThat(hashtags).containsExactly(
                new ParsedHashtag("PUBG", "PUBG"),
                new ParsedHashtag("pubg", "pubg"),
                new ParsedHashtag("Café", "Café")
        );
    }

    @Test
    void ignoresHashesInsideUrlsProgrammingNamesAndAdjacentText() {
        assertThat(parser.parse("https://example.com/#frag C# tag abc#hidden ##double"))
                .isEmpty();
    }

    @Test
    void acceptsTrailingPunctuationButDoesNotIncludeIt() {
        assertThat(parser.parse("#배그! #ranked, #clip."))
                .extracting(ParsedHashtag::displayName)
                .containsExactly("배그", "ranked", "clip");
    }

    @Test
    void ignoresEmptyAndOverLengthHashtagsWithoutRejectingContent() {
        String valid = "a".repeat(HashtagParser.MAX_HASHTAG_LENGTH);
        String tooLong = "b".repeat(HashtagParser.MAX_HASHTAG_LENGTH + 1);

        assertThat(parser.parse("# #" + valid + " #" + tooLong))
                .containsExactly(new ParsedHashtag(valid, valid));
    }

    @Test
    void lookupAcceptsOptionalHashAndRejectsPartialOrInvalidNames() {
        assertThat(parser.normalizeLookup(" #PuBg_1 ")).contains("PuBg_1");
        assertThat(parser.normalizeLookup("C#")).isEmpty();
        assertThat(parser.normalizeLookup("tag-name")).isEmpty();
        assertThat(parser.normalizeLookup("#")).isEmpty();
        assertThat(parser.normalizeLookup("a".repeat(51))).isEmpty();
    }
}
