package com.gamerin.backend.domain.post.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.global.logging.JsonLogContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class PostUploadConcurrencyFilter extends OncePerRequestFilter {

    private static final String BUSY_MESSAGE = "현재 업로드 요청이 많습니다. 잠시 후 다시 시도해주세요.";
    private static final Logger log = LoggerFactory.getLogger(PostUploadConcurrencyFilter.class);

    private final ObjectMapper objectMapper;
    private final Semaphore uploadSlots;
    private final long acquireTimeoutMillis;

    public PostUploadConcurrencyFilter(
            ObjectMapper objectMapper,
            int maxConcurrency,
            long acquireTimeoutMillis
    ) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("Post upload max concurrency must be at least 1.");
        }
        if (acquireTimeoutMillis < 0) {
            throw new IllegalArgumentException("Post upload acquire timeout must not be negative.");
        }
        this.objectMapper = objectMapper;
        this.uploadSlots = new Semaphore(maxConcurrency, true);
        this.acquireTimeoutMillis = acquireTimeoutMillis;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !"/api/v1/posts".equals(request.getRequestURI())
                || contentType == null
                || !contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        boolean acquired = false;
        try {
            acquired = uploadSlots.tryAcquire(acquireTimeoutMillis, TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("Post multipart upload rejected because all upload slots are busy");
                writeRejection(request, response, HttpStatus.TOO_MANY_REQUESTS);
                return;
            }
            filterChain.doFilter(request, response);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Post multipart upload wait was interrupted", ex);
            writeRejection(request, response, HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            if (acquired) {
                uploadSlots.release();
            }
        }
    }

    private void writeRejection(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status
    ) throws IOException {
        JsonLogContext.setFailureReason(request, BUSY_MESSAGE);
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", BUSY_MESSAGE);
        body.put("data", null);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
