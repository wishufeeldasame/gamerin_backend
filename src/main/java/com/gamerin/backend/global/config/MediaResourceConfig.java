package com.gamerin.backend.global.config;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MediaResourceConfig implements WebMvcConfigurer {

    private static final String POST_MEDIA_DIRECTORY = "post-media";

    private final String uploadLocation;

    public MediaResourceConfig(@Value("${app.media.upload-dir:uploads}") String uploadDir) {
        String location = Path.of(uploadDir)
                .toAbsolutePath()
                .normalize()
                .resolve(POST_MEDIA_DIRECTORY)
                .toUri()
                .toString();
        this.uploadLocation = location.endsWith("/") ? location : location + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/post-media/**")
                .addResourceLocations(uploadLocation);
    }
}
