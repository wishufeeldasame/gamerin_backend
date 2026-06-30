package com.gamerin.backend.domain.post.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.concurrent.Semaphore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;

class PostUploadConcurrencyFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void multipartPostAcquiresAndReleasesUploadSlot() throws Exception {
        PostUploadConcurrencyFilter filter = new PostUploadConcurrencyFilter(objectMapper, 1, 0);
        Semaphore uploadSlots = uploadSlots(filter);
        MockHttpServletRequest request = multipartPostRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = (req, res) -> assertThat(uploadSlots.availablePermits()).isZero();

        filter.doFilter(request, response, filterChain);

        assertThat(uploadSlots.availablePermits()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void saturatedUploadLimitReturnsTooManyRequestsWithoutCallingChain() throws Exception {
        PostUploadConcurrencyFilter filter = new PostUploadConcurrencyFilter(objectMapper, 1, 0);
        Semaphore uploadSlots = uploadSlots(filter);
        assertThat(uploadSlots.tryAcquire()).isTrue();
        MockHttpServletRequest request = multipartPostRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        try {
            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getContentAsString())
                    .contains("현재 업로드 요청이 많습니다. 잠시 후 다시 시도해주세요.")
                    .doesNotContain("Semaphore");
            assertThat(uploadSlots.availablePermits()).isZero();
            verify(filterChain, never()).doFilter(request, response);
        } finally {
            uploadSlots.release();
        }
    }

    @Test
    void interruptedWaitReturnsServiceUnavailableWithoutReleasingUnownedSlot() throws Exception {
        PostUploadConcurrencyFilter filter = new PostUploadConcurrencyFilter(objectMapper, 1, 5_000);
        Semaphore uploadSlots = uploadSlots(filter);
        assertThat(uploadSlots.tryAcquire()).isTrue();
        MockHttpServletRequest request = multipartPostRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        Thread.currentThread().interrupt();
        try {
            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(uploadSlots.availablePermits()).isZero();
            verify(filterChain, never()).doFilter(request, response);
        } finally {
            Thread.interrupted();
            uploadSlots.release();
        }
    }

    @Test
    void nonMultipartRequestBypassesUploadLimit() throws Exception {
        PostUploadConcurrencyFilter filter = new PostUploadConcurrencyFilter(objectMapper, 1, 0);
        Semaphore uploadSlots = uploadSlots(filter);
        assertThat(uploadSlots.tryAcquire()).isTrue();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/posts");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        try {
            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(200);
        } finally {
            uploadSlots.release();
        }
    }

    @Test
    void constructorRejectsInvalidUploadConcurrencyConfiguration() {
        assertThatThrownBy(() -> new PostUploadConcurrencyFilter(objectMapper, 0, 1_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max concurrency");

        assertThatThrownBy(() -> new PostUploadConcurrencyFilter(objectMapper, 1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acquire timeout");
    }

    @Test
    void concurrentMultipartRequestIsRejectedUntilFirstRequestReleasesSlot() throws Exception {
        PostUploadConcurrencyFilter filter = new PostUploadConcurrencyFilter(objectMapper, 1, 0);
        Semaphore uploadSlots = uploadSlots(filter);
        CountDownLatch firstRequestEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRequest = new CountDownLatch(1);
        MockHttpServletRequest firstRequest = multipartPostRequest();
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        FilterChain blockingChain = (request, response) -> {
            firstRequestEntered.countDown();
            try {
                if (!releaseFirstRequest.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release first upload request");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        };

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> firstRequestFuture = executor.submit(() -> {
                try {
                    filter.doFilter(firstRequest, firstResponse, blockingChain);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
            assertThat(firstRequestEntered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(uploadSlots.availablePermits()).isZero();

            MockHttpServletRequest secondRequest = multipartPostRequest();
            MockHttpServletResponse secondResponse = new MockHttpServletResponse();
            FilterChain secondChain = mock(FilterChain.class);
            filter.doFilter(secondRequest, secondResponse, secondChain);

            assertThat(secondResponse.getStatus()).isEqualTo(429);
            verify(secondChain, never()).doFilter(secondRequest, secondResponse);

            releaseFirstRequest.countDown();
            firstRequestFuture.get(3, TimeUnit.SECONDS);
        } finally {
            releaseFirstRequest.countDown();
        }

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(uploadSlots.availablePermits()).isEqualTo(1);
    }

    private MockHttpServletRequest multipartPostRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/posts");
        request.setContentType(MediaType.MULTIPART_FORM_DATA_VALUE + "; boundary=test");
        return request;
    }

    private Semaphore uploadSlots(PostUploadConcurrencyFilter filter) {
        return (Semaphore) ReflectionTestUtils.getField(filter, "uploadSlots");
    }
}
