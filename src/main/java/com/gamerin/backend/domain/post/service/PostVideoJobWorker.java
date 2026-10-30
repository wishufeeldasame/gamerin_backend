package com.gamerin.backend.domain.post.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 게시물 영상 작업 워커. 기본 스케줄러 스레드는 결제 복구 등 다른 작업과 함께 쓰므로,
 * 스케줄러는 대기 작업 확인만 하고 FFmpeg 실행은 전용 단일 스레드에서 한 건씩 처리한다.
 */
@Component
@ConditionalOnProperty(name = "app.media.video.processing.worker-enabled", havingValue = "true", matchIfMissing = true)
public class PostVideoJobWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PostVideoJobWorker.class);

    private final PostVideoJobService postVideoJobService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "post-video-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean recovered = new AtomicBoolean();

    public PostVideoJobWorker(PostVideoJobService postVideoJobService) {
        this.postVideoJobService = postVideoJobService;
    }

    @Scheduled(
            initialDelayString = "${app.media.video.processing.poll-initial-delay-ms:5000}",
            fixedDelayString = "${app.media.video.processing.poll-delay-ms:2000}")
    public void poll() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::drain);
        } catch (RejectedExecutionException ex) {
            draining.set(false);
        }
    }

    void drain() {
        try {
            // 재시작 복구는 같은 스레드에서 첫 처리 전에 한 번만 한다. 복구 전에 작업을 잡으면 처리 중인 작업을 되돌릴 수 있다.
            if (!recovered.get()) {
                postVideoJobService.recoverInterruptedJobs();
                recovered.set(true);
            }
            while (!Thread.currentThread().isInterrupted() && postVideoJobService.processNextJob()) {
                // 대기 작업이 남아 있으면 이어서 처리한다.
            }
        } catch (RuntimeException ex) {
            log.error("Post video worker stopped unexpectedly", ex);
        } finally {
            draining.set(false);
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
