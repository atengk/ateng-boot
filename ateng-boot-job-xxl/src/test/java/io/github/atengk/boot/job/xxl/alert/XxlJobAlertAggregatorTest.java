package io.github.atengk.boot.job.xxl.alert;

import io.github.atengk.boot.job.xxl.event.XxlJobAlertEvent;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * XXL-Job 三阶告警防风暴聚合与自愈引擎单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class XxlJobAlertAggregatorTest {

    private List<XxlJobAlertEvent> publishedEvents;
    private ApplicationEventPublisher eventPublisher;
    private XxlJobProperties properties;
    private XxlJobAlertAggregator aggregator;

    @BeforeEach
    void setUp() {
        publishedEvents = new CopyOnWriteArrayList<>();
        eventPublisher = event -> {
            if (event instanceof XxlJobAlertEvent alertEvent) {
                publishedEvents.add(alertEvent);
            }
        };

        properties = new XxlJobProperties();
        properties.setAlertEnabled(true);
        properties.setAlertSuppressDuration(Duration.ofMinutes(5));
        properties.setAlertSuppressThreshold(3); // 连续 3 次失败触发摘要

        aggregator = new XxlJobAlertAggregator(properties, eventPublisher, null);
    }

    @Test
    @DisplayName("测试首次任务失败立即推送首发告警卡片")
    void testInitialFailureDispatchedImmediately() {
        aggregator.onJobFailure("orderSyncJob", "{id: 1}", "trace-001", 120, new RuntimeException("DB Connection Timeout"));

        assertEquals(1, publishedEvents.size());
        XxlJobAlertEvent event = publishedEvents.get(0);
        assertEquals(XxlJobAlertEvent.AlertType.INITIAL_FAILURE, event.getAlertType());
        assertEquals("orderSyncJob", event.getJobHandler());
        assertEquals("trace-001", event.getTraceId());
        assertEquals(1, event.getFailureCount());
        assertNotNull(event.getThrowable());
    }

    @Test
    @DisplayName("测试连续高频失败在窗口期内静默抑制，防机器人 Webhook 封禁")
    void testFailureStormSuppression() {
        // 第一次失败：立即推送首发告警
        aggregator.onJobFailure("orderSyncJob", "{id: 1}", "trace-001", 100, new RuntimeException("Err 1"));
        assertEquals(1, publishedEvents.size());

        // 第二次失败：未达到阈值 (3次)，且未达到超时周期，被平滑静默抑制
        aggregator.onJobFailure("orderSyncJob", "{id: 2}", "trace-002", 110, new RuntimeException("Err 2"));
        assertEquals(1, publishedEvents.size(), "中间重复失败应当被静默抑制");

        // 第三次失败：达到连续失败阈值 (3次)，触发周期摘要卡片
        aggregator.onJobFailure("orderSyncJob", "{id: 3}", "trace-003", 120, new RuntimeException("Err 3"));
        assertEquals(2, publishedEvents.size(), "达到阈值时应当触发聚合摘要卡片");

        XxlJobAlertEvent summaryEvent = publishedEvents.get(1);
        assertEquals(XxlJobAlertEvent.AlertType.FAILURE_SUMMARY, summaryEvent.getAlertType());
        assertEquals(3, summaryEvent.getFailureCount());

        // 第四、五次失败：处于下一阶段静默抑制窗口内，必须被平滑静默抑制，绝不穿透发送！
        aggregator.onJobFailure("orderSyncJob", "{id: 4}", "trace-004", 100, new RuntimeException("Err 4"));
        assertEquals(2, publishedEvents.size(), "第四次失败应当继续被平滑静默抑制");
        aggregator.onJobFailure("orderSyncJob", "{id: 5}", "trace-005", 100, new RuntimeException("Err 5"));
        assertEquals(2, publishedEvents.size(), "第五次失败应当继续被平滑静默抑制");

        // 第六次失败：累计再次达到步进阈值 (自上次摘要后又满 3 次，累计 6 次)，触发第二次聚合摘要卡片
        aggregator.onJobFailure("orderSyncJob", "{id: 6}", "trace-006", 130, new RuntimeException("Err 6"));
        assertEquals(3, publishedEvents.size(), "再次达到步进阈值时应当触发第二次聚合摘要卡片");
        XxlJobAlertEvent secondSummaryEvent = publishedEvents.get(2);
        assertEquals(XxlJobAlertEvent.AlertType.FAILURE_SUMMARY, secondSummaryEvent.getAlertType());
        assertEquals(6, secondSummaryEvent.getFailureCount());

        // 随后成功执行一次：触发自愈恢复，累计失败总数应为 6
        aggregator.onJobSuccess("orderSyncJob", "{id: 7}", "trace-007", 50);
        assertEquals(4, publishedEvents.size());
        XxlJobAlertEvent recoveryEvent = publishedEvents.get(3);
        assertEquals(XxlJobAlertEvent.AlertType.RECOVERY, recoveryEvent.getAlertType());
        assertEquals(6, recoveryEvent.getFailureCount(), "自愈恢复应当携带期间累计发生的 6 次失败总数");
    }

    @Test
    @DisplayName("测试连续失败后首次成功执行推送故障自愈恢复卡片并重置状态")
    void testSelfHealingRecoveryDispatchedOnFirstSuccess() {
        // 触发两次失败
        aggregator.onJobFailure("inventoryJob", "p1", "trace-1", 50, new RuntimeException("Redis Timeout"));
        aggregator.onJobFailure("inventoryJob", "p2", "trace-2", 55, new RuntimeException("Redis Timeout"));
        assertEquals(1, publishedEvents.size());

        // 随后成功执行一次
        aggregator.onJobSuccess("inventoryJob", "p3", "trace-3", 30);
        assertEquals(2, publishedEvents.size());

        XxlJobAlertEvent recoveryEvent = publishedEvents.get(1);
        assertEquals(XxlJobAlertEvent.AlertType.RECOVERY, recoveryEvent.getAlertType());
        assertEquals("inventoryJob", recoveryEvent.getJobHandler());
        assertEquals(2, recoveryEvent.getFailureCount(), "自愈事件应当携带期间累计发生的失败总次数");
        assertNotNull(recoveryEvent.getFailureDuration());

        // 再次成功执行时不应再重复发送自愈恢复卡片
        aggregator.onJobSuccess("inventoryJob", "p4", "trace-4", 25);
        assertEquals(2, publishedEvents.size(), "健康状态下的后续正常调用不重复推送恢复通知");
    }

    @Test
    @DisplayName("测试当全局禁用告警时静默不发任何事件")
    void testDisabledAlertDoesNothing() {
        properties.setAlertEnabled(false);
        aggregator.onJobFailure("disabledJob", "p", "t", 10, new RuntimeException("Err"));
        aggregator.onJobSuccess("disabledJob", "p", "t", 10);
        assertTrue(publishedEvents.isEmpty());
    }

    @Test
    @DisplayName("并发压测：高并发下同任务首发、摘要与自愈恢复状态转移严格原子且零重复")
    void testConcurrentFailureSummaryAndSingleRecoveryAtomicity() throws Exception {
        properties.setAlertSuppressThreshold(10);
        properties.setAlertSuppressDuration(Duration.ofMinutes(10));

        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(16);
        pool.setMaxPoolSize(16);
        pool.setThreadNamePrefix("alert-stress-");
        pool.initialize();

        try {
            int failureCalls = 160;
            CountDownLatch failureGate = new CountDownLatch(1);
            List<CompletableFuture<Void>> failFutures = new CopyOnWriteArrayList<>();
            for (int i = 0; i < failureCalls; i++) {
                final int idx = i;
                failFutures.add(CompletableFuture.runAsync(() -> {
                    try {
                        failureGate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    aggregator.onJobFailure("concurrentJob", "p-" + idx, "trace-" + idx, 10, new RuntimeException("err"));
                }, pool));
            }
            failureGate.countDown();
            CompletableFuture.allOf(failFutures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);

            long initialCount = publishedEvents.stream()
                    .filter(e -> e.getAlertType() == XxlJobAlertEvent.AlertType.INITIAL_FAILURE)
                    .count();
            long summaryCount = publishedEvents.stream()
                    .filter(e -> e.getAlertType() == XxlJobAlertEvent.AlertType.FAILURE_SUMMARY)
                    .count();
            assertEquals(1, initialCount, "高并发下必须且仅触发 1 次首发告警");
            assertEquals(16, summaryCount, "第 10、20...160 次失败应共触发 16 次聚合摘要");

            // 16 线程同时报告成功，必须且仅推送 1 次 RECOVERY 事件且失败次数准确为 160
            CountDownLatch recoveryGate = new CountDownLatch(1);
            List<CompletableFuture<Void>> recoveryFutures = new CopyOnWriteArrayList<>();
            for (int i = 0; i < 16; i++) {
                final int idx = i;
                recoveryFutures.add(CompletableFuture.runAsync(() -> {
                    try {
                        recoveryGate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    aggregator.onJobSuccess("concurrentJob", "ok-" + idx, "trace-ok-" + idx, 5);
                }, pool));
            }
            recoveryGate.countDown();
            CompletableFuture.allOf(recoveryFutures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);

            List<XxlJobAlertEvent> recoveries = publishedEvents.stream()
                    .filter(e -> e.getAlertType() == XxlJobAlertEvent.AlertType.RECOVERY)
                    .toList();
            assertEquals(1, recoveries.size(), "高并发下自愈恢复事件必须且仅触发 1 次");
            assertEquals(160, recoveries.get(0).getFailureCount());
        } finally {
            pool.shutdown();
        }
    }

}
