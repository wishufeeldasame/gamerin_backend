package com.gamerin.backend.domain.post.service;

import java.net.URI;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExternalLinkMetadataService {

    public ExternalLinkMetadata fetch(String url) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();

            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "External links must start with http or https.");
            }
            if (host == null || host.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "External link host is invalid.");
            }

            String normalizedHost = normalizeHost(host);
            String title = buildTitle(normalizedHost);
            String thumbnailUrl = resolveThumbnailUrl(uri, normalizedHost);

            return new ExternalLinkMetadata(
                    url.trim(),
                    normalizedHost,
                    title,
                    url.trim(),
                    thumbnailUrl
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "External link URL is invalid.", ex);
        }
    }

    private String normalizeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("www.")) {
            return normalized.substring(4);
        }
        return normalized;
    }

    private String buildTitle(String host) {
        if (host.equals("youtube.com") || host.equals("youtu.be")) {
            return "YouTube";
        }
        return host;
    }

    private String resolveThumbnailUrl(URI uri, String host) {
        String videoId = extractYouTubeVideoId(uri, host);
        if (videoId == null) {
            return null;
        }
        return "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
    }

    private String extractYouTubeVideoId(URI uri, String host) {
        if (host.equals("youtu.be")) {
            String path = uri.getPath();
            if (path == null || path.isBlank() || path.equals("/")) {
                return null;
            }
            return path.substring(1);
        }

        if (!host.equals("youtube.com")) {
            return null;
        }

        String path = uri.getPath();
        if (path == null) {
            return null;
        }

        if (path.equals("/watch")) {
            String query = uri.getQuery();
            if (query == null) {
                return null;
            }
            for (String entry : query.split("&")) {
                String[] tokens = entry.split("=", 2);
                if (tokens.length == 2 && tokens[0].equals("v") && !tokens[1].isBlank()) {
                    return tokens[1];
                }
            }
            return null;
        }

        if (path.startsWith("/shorts/")) {
            return path.substring("/shorts/".length());
        }

        if (path.startsWith("/embed/")) {
            return path.substring("/embed/".length());
        }

        return null;
    }

    public record ExternalLinkMetadata(
            String url,
            String host,
            String title,
            String description,
            String thumbnailUrl
    ) {
    }
}
