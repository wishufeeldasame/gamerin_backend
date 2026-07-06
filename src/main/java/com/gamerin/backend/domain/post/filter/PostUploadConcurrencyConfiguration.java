package com.gamerin.backend.domain.post.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class PostUploadConcurrencyConfiguration {

    @Bean
    public PostUploadConcurrencyFilter postUploadConcurrencyFilter(
            ObjectMapper objectMapper,
            @Value("${app.media.upload.max-concurrency:1}") int maxConcurrency,
            @Value("${app.media.upload.acquire-timeout-ms:5000}") long acquireTimeoutMillis
    ) {
        return new PostUploadConcurrencyFilter(objectMapper, maxConcurrency, acquireTimeoutMillis);
    }

    @Bean
    public FilterRegistrationBean<PostUploadConcurrencyFilter> disablePostUploadConcurrencyFilterAutoRegistration(
            PostUploadConcurrencyFilter filter
    ) {
        FilterRegistrationBean<PostUploadConcurrencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
