package com.gamerin.backend.domain.post.moderation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.mockito.ArgumentCaptor;

import com.gamerin.backend.domain.post.moderation.OpenAiModerationClient.ModerationDecision;
import com.gamerin.backend.domain.post.moderation.OpenAiModerationClient.ModerationInput;
import com.gamerin.backend.domain.post.service.AnimatedGifProcessor;

class ContentModerationServiceTest {

    private OpenAiModerationClient openAiModerationClient;
    private ImageModerationPreprocessor imageModerationPreprocessor;
    private AnimatedGifProcessor animatedGifProcessor;
    private ContentModerationService contentModerationService;

    @BeforeEach
    void setUp() {
        openAiModerationClient = mock(OpenAiModerationClient.class);
        imageModerationPreprocessor = mock(ImageModerationPreprocessor.class);
        animatedGifProcessor = mock(AnimatedGifProcessor.class);
        contentModerationService = new ContentModerationService(
                openAiModerationClient,
                imageModerationPreprocessor,
                mock(VideoFrameExtractor.class),
                animatedGifProcessor
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

    @Test
    void animatedGifModeratesFirstMiddleAndLastFrames() {
        MockMultipartFile gif = new MockMultipartFile(
                "mediaFiles",
                "animation.gif",
                "image/gif",
                "gif".getBytes()
        );
        List<BufferedImage> frames = List.of(
                new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB),
                new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB),
                new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        );
        when(animatedGifProcessor.isGif(gif)).thenReturn(true);
        when(animatedGifProcessor.extractModerationFrames(gif)).thenReturn(frames);
        when(imageModerationPreprocessor.toDataUrl(any(BufferedImage.class)))
                .thenReturn("data:image/jpeg;base64,first")
                .thenReturn("data:image/jpeg;base64,middle")
                .thenReturn("data:image/jpeg;base64,last");
        when(openAiModerationClient.moderate(anyList()))
                .thenReturn(new ModerationDecision(false, List.of()));

        contentModerationService.assertPostAllowed(null, List.of(gif));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModerationInput>> inputsCaptor = ArgumentCaptor.forClass(List.class);
        verify(openAiModerationClient).moderate(inputsCaptor.capture());
        assertThat(inputsCaptor.getValue())
                .extracting(ModerationInput::imageDataUrl)
                .containsExactly(
                        "data:image/jpeg;base64,first",
                        "data:image/jpeg;base64,middle",
                        "data:image/jpeg;base64,last"
                );
    }
}
