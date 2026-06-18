package com.gamerin.backend.domain.post.moderation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.entity.PostMediaType;
import com.gamerin.backend.domain.post.moderation.OpenAiModerationClient.ModerationDecision;
import com.gamerin.backend.domain.post.moderation.OpenAiModerationClient.ModerationInput;

@Service
public class ContentModerationService {

    // Gameplay violence is expected in a gaming feed; graphic violence remains blocked below.
    private static final Set<String> ALLOWED_GAMEPLAY_CATEGORIES = Set.of(
            "violence"
    );

    private static final Set<String> BLOCKED_CATEGORIES = Set.of(
            "harassment",
            "harassment/threatening",
            "hate",
            "hate/threatening",
            "illicit",
            "illicit/violent",
            "self-harm",
            "self-harm/intent",
            "self-harm/instructions",
            "sexual",
            "sexual/minors",
            "violence/graphic"
    );

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
        assertTextAndMediaAllowed(content, mediaFiles);
    }

    public void assertMessageAllowed(String content, List<MultipartFile> mediaFiles) {
        assertTextAndMediaAllowed(content, mediaFiles);
    }

    private void assertTextAndMediaAllowed(String content, List<MultipartFile> mediaFiles) {
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

        if (decision.flaggedCategories().isEmpty()) {
            throwModerationRejection("unknown");
        }

        List<String> blockedCategories = decision.flaggedCategories()
                .stream()
                .filter(this::isBlockedCategory)
                .toList();
        if (blockedCategories.isEmpty()) {
            boolean onlyAllowedGameplayCategories = decision.flaggedCategories()
                    .stream()
                    .allMatch(this::isAllowedGameplayCategory);
            if (onlyAllowedGameplayCategories) {
                return;
            }
            throwModerationRejection("unknown");
        }

        throwModerationRejection(String.join(", ", blockedCategories));
    }

    private void throwModerationRejection(String categories) {
        throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Content violates moderation policy: " + categories
        );
    }

    private boolean isBlockedCategory(String category) {
        return BLOCKED_CATEGORIES.contains(category);
    }

    private boolean isAllowedGameplayCategory(String category) {
        return ALLOWED_GAMEPLAY_CATEGORIES.contains(category);
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
            if (normalized.endsWith(".mp4") || normalized.endsWith(".mov")
                    || normalized.endsWith(".m4v")) {
                return PostMediaType.VIDEO;
            }
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported media file type.");
    }
}
