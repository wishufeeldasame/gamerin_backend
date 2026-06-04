package com.gamerin.backend.domain.post.moderation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.moderation.OpenAiModerationClient.ModerationDecision;

class ContentModerationServiceTest {

    private OpenAiModerationClient openAiModerationClient;
    private ContentModerationService contentModerationService;

    @BeforeEach
    void setUp() {
        openAiModerationClient = mock(OpenAiModerationClient.class);
        contentModerationService = new ContentModerationService(
                openAiModerationClient,
                mock(ImageModerationPreprocessor.class),
                mock(VideoFrameExtractor.class)
        );
    }

    @Test
    void assertTextAllowedAllowsGeneralGameplayViolence() {
        when(openAiModerationClient.moderate(anyList()))
                .thenReturn(new ModerationDecision(true, List.of("violence")));

        assertThatCode(() -> contentModerationService.assertTextAllowed("valorant clutch clip"))
                .doesNotThrowAnyException();
    }

    @Test
    void assertTextAllowedRejectsGraphicViolence() {
        when(openAiModerationClient.moderate(anyList()))
                .thenReturn(new ModerationDecision(true, List.of("violence/graphic")));

        assertThatThrownBy(() -> contentModerationService.assertTextAllowed("graphic clip"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    }

    @Test
    void assertTextAllowedRejectsHarassment() {
        when(openAiModerationClient.moderate(anyList()))
                .thenReturn(new ModerationDecision(true, List.of("harassment")));

        assertThatThrownBy(() -> contentModerationService.assertTextAllowed("toxic comment"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    }

    @Test
    void assertTextAllowedRejectsWhenGameplayViolenceIsMixedWithBlockedCategory() {
        when(openAiModerationClient.moderate(anyList()))
                .thenReturn(new ModerationDecision(true, List.of("violence", "harassment")));

        assertThatThrownBy(() -> contentModerationService.assertTextAllowed("toxic game clip"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    }

    @Test
    void assertTextAllowedRejectsFlaggedUnknownCategory() {
        when(openAiModerationClient.moderate(anyList()))
                .thenReturn(new ModerationDecision(true, List.of()));

        assertThatThrownBy(() -> contentModerationService.assertTextAllowed("unknown flagged content"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    }

    @Test
    void assertTextAllowedRejectsFlaggedFutureCategory() {
        when(openAiModerationClient.moderate(anyList()))
                .thenReturn(new ModerationDecision(true, List.of("new-risk-category")));

        assertThatThrownBy(() -> contentModerationService.assertTextAllowed("future flagged content"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    }
}
