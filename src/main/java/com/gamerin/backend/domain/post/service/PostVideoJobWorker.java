package com.gamerin.backend.domain.post.service;

import com.gamerin.backend.domain.upload.service.UploadJobService;
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
 * 기존 게시물 영상과 opt-in 게시물·DM 업로드를 전용 단일 스레드에서 한 건씩 처리한다.
 * 기본 스케줄러는 결제 복구 등 다른 작업과 공유하므로 검열·FFmpeg를 직접 실행하지 않는다.
 * shortcut: 재시작 복구는 단일 백엔드 인스턴스 전제이며, 수평 확장 시 lease 기반 복구로 바꿔야 한다.
 */
@Component
@ConditionalOnProperty(name = "app.media.video.processing.worker-enabled", havingValue = "true", matchIfMissing = true)
public class PostVideoJobWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PostVideoJobWorker.class);

    private final PostVideoJobService postVideoJobService;
    private final UploadJobService uploadJobService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "post-video-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean recovered = new AtomicBoolean();

    public PostVideoJobWorker(PostVideoJobService postVideoJobService,
            UploadJobService uploadJobService) {
        this.postVideoJobService = postVideoJobService;
        this.uploadJobService = uploadJobService;
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
                uploadJobService.recoverInterruptedJobs();
                recovered.set(true);
            }
            uploadJobService.cleanTerminalFiles();
            while (!Thread.currentThread().isInterrupted()) {
                boolean video = postVideoJobService.processNextJob();
                if (Thread.currentThread().isInterrupted()) break;
                boolean upload = uploadJobService.processNextJob();
                if (!video && !upload) break;
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
