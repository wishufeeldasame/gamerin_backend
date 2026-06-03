package com.gamerin.backend.domain.post.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.gamerin.backend.domain.post.moderation.OpenAiModerationClient.ModerationInput;

class OpenAiModerationClientTest {

    @Test
    void splitRequestsByImageLimitKeepsOneImagePerRequest() {
        ModerationInput text = ModerationInput.text("video post");
        ModerationInput firstFrame = ModerationInput.image("data:image/jpeg;base64,first");
        ModerationInput middleFrame = ModerationInput.image("data:image/jpeg;base64,middle");
        ModerationInput lastFrame = ModerationInput.image("data:image/jpeg;base64,last");

        List<List<ModerationInput>> requests = OpenAiModerationClient.splitRequestsByImageLimit(List.of(
                text,
                firstFrame,
                middleFrame,
                lastFrame
        ));

        assertThat(requests).hasSize(3);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request).contains(text);
            assertThat(request)
                    .filteredOn(input -> "image_url".equals(input.type()))
                    .hasSize(1);
        });
        assertThat(requests.get(0)).containsExactly(text, firstFrame);
        assertThat(requests.get(1)).containsExactly(text, middleFrame);
        assertThat(requests.get(2)).containsExactly(text, lastFrame);
    }

    @Test
    void splitRequestsByImageLimitLeavesTextOnlyRequestUntouched() {
        ModerationInput text = ModerationInput.text("text only");

        List<List<ModerationInput>> requests = OpenAiModerationClient.splitRequestsByImageLimit(List.of(text));

        assertThat(requests).containsExactly(List.of(text));
    }

    @Test
    void splitRequestsByImageLimitLeavesSingleImageRequestUntouched() {
        ModerationInput text = ModerationInput.text("image post");
        ModerationInput image = ModerationInput.image("data:image/jpeg;base64,image");

        List<List<ModerationInput>> requests = OpenAiModerationClient.splitRequestsByImageLimit(List.of(text, image));

        assertThat(requests).containsExactly(List.of(text, image));
    }
}
