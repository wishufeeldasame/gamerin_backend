package com.gamerin.backend.domain.weeklyreport.scheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.gamerin.backend.domain.weeklyreport.service.WeeklyReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WeeklyReportScheduler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportScheduler.class);
    private final WeeklyReportService weeklyReportService;
    // 공용 스케줄러를 외부 API 호출·사용자 사이 대기로 막지 않는다.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "weekly-report-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();

    public WeeklyReportScheduler(WeeklyReportService weeklyReportService) {
        this.weeklyReportService = weeklyReportService;
    }

    /** 매주 월요일 04:00 KST에 직전 주(월~일) 리포트를 만든다. */
    @Scheduled(cron = "0 0 4 * * MON", zone = "Asia/Seoul")
    public void generateWeeklyReports() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    weeklyReportService.generateForActiveMembers();
                } catch (RuntimeException e) {
                    log.error("Weekly report batch stopped unexpectedly", e);
                } finally {
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            running.set(false);
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
