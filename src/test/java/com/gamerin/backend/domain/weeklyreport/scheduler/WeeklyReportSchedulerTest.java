package com.gamerin.backend.domain.weeklyreport.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.gamerin.backend.domain.weeklyreport.service.WeeklyReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WeeklyReportSchedulerTest {

    private final WeeklyReportService service = mock(WeeklyReportService.class);
    private final WeeklyReportScheduler scheduler = new WeeklyReportScheduler(service);
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() throws InterruptedException {
        release.countDown();
        scheduler.destroy();
    }

    @Test
    void returnsWithoutBlockingSchedulerAndSkipsOverlappingBatch() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<String> workerThread = new AtomicReference<>();
        doAnswer(invocation -> {
            workerThread.set(Thread.currentThread().getName());
            entered.countDown();
            release.await();
            return null;
        }).when(service).generateForActiveMembers();

        assertTimeoutPreemptively(Duration.ofSeconds(1), scheduler::generateWeeklyReports);
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(workerThread.get()).isEqualTo("weekly-report-worker");
        scheduler.generateWeeklyReports();
        verify(service, times(1)).generateForActiveMembers();
    }

    @Test
    void failureAllowsNextBatch() {
        CountDownLatch recovered = new CountDownLatch(1);
        doThrow(new IllegalStateException("batch failed"))
                .doAnswer(invocation -> {
                    recovered.countDown();
                    release.await();
                    return null;
                }).when(service).generateForActiveMembers();

        scheduler.generateWeeklyReports();
        await().atMost(Duration.ofSeconds(3)).until(() -> {
            scheduler.generateWeeklyReports();
            return recovered.await(10, TimeUnit.MILLISECONDS);
        });
        verify(service, times(2)).generateForActiveMembers();
    }

    @Test
    void shutdownInterruptsWorkerAndRejectsNewBatch() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
            }
            return null;
        }).when(service).generateForActiveMembers();
        scheduler.generateWeeklyReports();
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();

        scheduler.destroy();

        assertThat(interrupted.await(3, TimeUnit.SECONDS)).isTrue();
        scheduler.generateWeeklyReports();
        verify(service, times(1)).generateForActiveMembers();
    }
}
