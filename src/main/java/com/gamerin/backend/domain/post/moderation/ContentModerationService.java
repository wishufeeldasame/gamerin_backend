package com.gamerin.backend.domain.post.moderation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.entity.PostMediaType;
import com.gamerin.backend.domain.post.moderation.OpenAiModerationClient.ModerationDecision;
import com.gamerin.backend.domain.post.moderation.OpenAiModerationClient.ModerationInput;

@Service
public class ContentModerationService {

    private final OpenAiModerationClient openAiModerationClient;
    private final ImageModerationPreprocessor imageModerationPreprocessor;
    private final VideoFrameExtractor videoFrameExtractor;

    public ContentModerationService(
            OpenAiModerationClient openAiModerationClient,
            ImageModerationPreprocessor imageModerationPreprocessor,
            VideoFrameExtractor videoFrameExtractor
    ) {
        this.openAiModerationClient = openAiModerationClient;
        this.imageModerationPreprocessor = imageModerationPreprocessor;
        this.videoFrameExtractor = videoFrameExtractor;
    }

    public void assertTextAllowed(String content) {
        if (content == null || content.isBlank()) {
            return;
        }

        assertAllowed(List.of(ModerationInput.text(content)));
    }

    public void assertPostAllowed(String content, List<MultipartFile> mediaFiles) {
        List<ModerationInput> inputs = new ArrayList<>();
        if (content != null && !content.isBlank()) {
            inputs.add(ModerationInput.text(content));
        }

        if (mediaFiles != null) {
            for (MultipartFile mediaFile : mediaFiles) {
                if (mediaFile == null || mediaFile.isEmpty()) {
                    continue;
                }

                PostMediaType mediaType = resolveMediaType(mediaFile);
                if (mediaType == PostMediaType.IMAGE) {
                    inputs.add(ModerationInput.image(imageModerationPreprocessor.toDataUrl(mediaFile)));
                } else {
                    videoFrameExtractor.extractFrameDataUrls(mediaFile)
                            .stream()
                            .map(ModerationInput::image)
                            .forEach(inputs::add);
                }
            }
        }

        assertAllowed(inputs);
    }

    private void assertAllowed(List<ModerationInput> inputs) {
        if (inputs.isEmpty()) {
            return;
        }

        ModerationDecision decision = openAiModerationClient.moderate(inputs);
        if (!decision.flagged()) {
            return;
        }

        String categories = decision.flaggedCategories().isEmpty()
                ? "unknown"
                : String.join(", ", decision.flaggedCategories());
        throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Content violates moderation policy: " + categories
        );
    }

    private PostMediaType resolveMediaType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null) {
            String normalized = contentType.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("image/")) {
                return PostMediaType.IMAGE;
            }
            if (normalized.startsWith("video/")) {
                return PostMediaType.VIDEO;
            }
        }

        String fileName = file.getOriginalFilename();
        if (fileName != null) {
            String normalized = fileName.toLowerCase(Locale.ROOT);
            if (normalized.endsWith(".jpg") || normalized.endsWith(".jpeg") || normalized.endsWith(".png")
                    || normalized.endsWith(".gif") || normalized.endsWith(".webp")) {
                return PostMediaType.IMAGE;
            }
            if (normalized.endsWith(".mp4") || normalized.endsWith(".mov") || normalized.endsWith(".webm")
                    || normalized.endsWith(".m4v")) {
                return PostMediaType.VIDEO;
            }
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported media file type.");
    }
}
