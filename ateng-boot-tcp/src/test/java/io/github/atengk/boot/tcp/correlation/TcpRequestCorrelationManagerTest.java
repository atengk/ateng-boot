package io.github.atengk.boot.tcp.correlation;

import io.github.atengk.boot.tcp.exception.TcpException;
import io.github.atengk.boot.tcp.exception.TcpTimeoutException;
import io.github.atengk.boot.tcp.metric.TcpMetricsCollector;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCP 全双工请求-响应相关性管理器单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 全双工请求-响应相关性管理器单元测试")
class TcpRequestCorrelationManagerTest {

    private TcpRequestCorrelationManager correlationManager;

    @BeforeEach
    void setUp() {
        correlationManager = new TcpRequestCorrelationManager();
    }

    @AfterEach
    void tearDown() {
        if (correlationManager != null) {
            correlationManager.close();
        }
    }

    @Test
    @DisplayName("验证全双工正常应答精准兑现")
    void testNormalRequestResponse() throws Exception {
        String requestId = "REQ-1001";
        CompletableFuture<TcpPacket> future = correlationManager.register(requestId, Duration.ofSeconds(5));

        assertThat(correlationManager.hasPendingRequests()).isTrue();
        assertThat(correlationManager.getPendingCount()).isEqualTo(1);

        TcpPacket response = TcpPacket.of("ECHO", requestId, TcpPayload.of("PONG"));
        boolean matched = correlationManager.complete(requestId, response);

        assertThat(matched).isTrue();
        assertThat(correlationManager.getPendingCount()).isEqualTo(0);

        TcpPacket result = future.get(1, TimeUnit.SECONDS);
        assertThat(result.getRequestId()).isEqualTo(requestId);
        assertThat(result.getPayload().asString()).isEqualTo("PONG");
    }

    @Test
    @DisplayName("验证基于 JSON 载荷通过 tryComplete 自动识别提取 requestId 并唤醒")
    void testTryCompleteWithJsonPayload() throws Exception {
        String requestId = "REQ-2002";
        CompletableFuture<TcpPacket> future = correlationManager.register(requestId, Duration.ofSeconds(5));

        String jsonResp = "{\"requestId\":\"" + requestId + "\",\"status\":\"OK\",\"data\":\"success\"}";
        TcpPayload payload = TcpPayload.of(jsonResp);

        boolean handled = correlationManager.tryComplete(payload);
        assertThat(handled).isTrue();
        assertThat(correlationManager.getPendingCount()).isEqualTo(0);

        TcpPacket result = future.get(1, TimeUnit.SECONDS);
        assertThat(result.getRequestId()).isEqualTo(requestId);
        assertThat(result.getPayload().asString()).isEqualTo(jsonResp);
    }

    @Test
    @DisplayName("验证超时自动熔断抛出 TcpTimeoutException 并清理挂起句柄杜绝内存泄漏")
    void testTimeoutEviction() {
        String requestId = "REQ-TIMEOUT";
        CompletableFuture<TcpPacket> future = correlationManager.register(requestId, Duration.ofMillis(100));

        assertThat(correlationManager.getPendingCount()).isEqualTo(1);

        assertThatThrownBy(future::join)
                .isInstanceOf(Exception.class)
                .hasCauseInstanceOf(TcpTimeoutException.class);

        // 验证超时后映射表自动剔除，内存归零
        assertThat(correlationManager.getPendingCount()).isEqualTo(0);
        assertThat(correlationManager.hasPendingRequests()).isFalse();
    }

    @Test
    @DisplayName("验证调用方主动 cancel 时自动清理映射表与定时任务")
    void testFutureCancellation() {
        String requestId = "REQ-CANCEL";
        CompletableFuture<TcpPacket> future = correlationManager.register(requestId, Duration.ofSeconds(10));

        assertThat(correlationManager.getPendingCount()).isEqualTo(1);
        future.cancel(true);

        assertThat(correlationManager.getPendingCount()).isEqualTo(0);
        assertThat(correlationManager.hasPendingRequests()).isFalse();
    }

    @Test
    @DisplayName("验证达到最大挂起请求数时触发过载熔断")
    void testCapacityLimit() {
        correlationManager.setMaxPendingRequests(2);

        correlationManager.register("REQ-1", Duration.ofSeconds(10));
        correlationManager.register("REQ-2", Duration.ofSeconds(10));

        assertThatThrownBy(() -> correlationManager.register("REQ-3", Duration.ofSeconds(10)))
                .isInstanceOf(TcpException.class)
                .hasMessageContaining("待处理 TCP RPC 请求数量达到上限");
    }

    @Test
    @DisplayName("验证 close 时所有挂起请求被安全异常中止")
    void testCloseCancelsPending() {
        CompletableFuture<TcpPacket> f1 = correlationManager.register("REQ-A", Duration.ofSeconds(10));
        CompletableFuture<TcpPacket> f2 = correlationManager.register("REQ-B", Duration.ofSeconds(10));

        correlationManager.close();

        assertThatThrownBy(f1::join)
                .isInstanceOf(Exception.class)
                .hasCauseInstanceOf(TcpException.class);
        assertThatThrownBy(f2::join)
                .isInstanceOf(Exception.class)
                .hasCauseInstanceOf(TcpException.class);

        assertThat(correlationManager.getPendingCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("验证 failAll 确定性补偿终止所有挂起在途 Future 并清空映射表")
    void testFailAllCompensatesPendingFutures() {
        CompletableFuture<TcpPacket> f1 = correlationManager.register("REQ-FAIL-1", Duration.ofSeconds(10));
        CompletableFuture<TcpPacket> f2 = correlationManager.register("REQ-FAIL-2", Duration.ofSeconds(10));

        assertThat(correlationManager.getPendingCount()).isEqualTo(2);

        IllegalStateException cause = new IllegalStateException("TCP 物理连接异常中断");
        correlationManager.failAll(cause);

        assertThatThrownBy(f1::join)
                .isInstanceOf(Exception.class)
                .hasCause(cause);
        assertThatThrownBy(f2::join)
                .isInstanceOf(Exception.class)
                .hasCause(cause);

        assertThat(correlationManager.getPendingCount()).isEqualTo(0);
        assertThat(correlationManager.hasPendingRequests()).isFalse();
    }

    @Test
    @DisplayName("验证关联请求完成时正确激活 TcpMetricsCollector 耗时指标打点")
    void testMetricsCollectorDurationRecording() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TcpMetricsCollector collector = new TcpMetricsCollector(meterRegistry, null);
        correlationManager.setMetricsCollector(collector);

        String requestId = "REQ-METRIC-1";
        correlationManager.register(requestId, Duration.ofSeconds(5));

        TcpPacket response = TcpPacket.of("TEST", requestId, TcpPayload.of("OK"));
        boolean matched = correlationManager.complete(requestId, response);
        assertThat(matched).isTrue();

        Timer timer = meterRegistry.find(TcpMetricsCollector.METRIC_REQUEST_DURATION).timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("验证实现 DisposableBean 且在 close/destroy 后支持惰性安全重建 HashedWheelTimer")
    void testDisposableBeanDestroyAndLazyTimerRebuildAfterClose() throws Exception {
        assertThat(correlationManager).isInstanceOf(DisposableBean.class);

        CompletableFuture<TcpPacket> pendingBeforeDestroy = correlationManager.register("REQ-BEFORE-DESTROY", Duration.ofSeconds(5));
        ((DisposableBean) correlationManager).destroy();

        assertThatThrownBy(pendingBeforeDestroy::join)
                .isInstanceOf(Exception.class)
                .hasCauseInstanceOf(TcpException.class);
        assertThat(correlationManager.getPendingCount()).isEqualTo(0);

        // destroy/close 后再次 register 应惰性重建时间轮而非抛出 cannot be started once stopped
        CompletableFuture<TcpPacket> reopened = correlationManager.register("REQ-AFTER-DESTROY", Duration.ofSeconds(3));
        assertThat(correlationManager.complete("REQ-AFTER-DESTROY",
                TcpPacket.of("ECHO", "REQ-AFTER-DESTROY", TcpPayload.of("REBUILT_OK")))).isTrue();
        assertThat(reopened.get(1, TimeUnit.SECONDS).getPayload().asString()).isEqualTo("REBUILT_OK");

        // 重建后的时间轮仍具备超时驱动能力
        CompletableFuture<TcpPacket> timeoutAfterRebuild = correlationManager.register("REQ-AFTER-TIMEOUT", Duration.ofMillis(80));
        assertThatThrownBy(timeoutAfterRebuild::join)
                .isInstanceOf(Exception.class)
                .hasCauseInstanceOf(TcpTimeoutException.class);
    }

    @Test
    @DisplayName("验证高并发下 register 与 close 交叉执行不抛 IllegalStateException 且关闭后零残留挂起句柄")
    void testConcurrentRegisterAndCloseNoRaceOrLeak() throws Exception {
        org.springframework.core.task.SimpleAsyncTaskExecutor executor = new org.springframework.core.task.SimpleAsyncTaskExecutor("test-corr-race-");
        try {
            int workerCount = 6;
            int rounds = 40;
            java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(workerCount);
            java.util.List<Throwable> unexpectedErrors = new java.util.concurrent.CopyOnWriteArrayList<>();

            for (int w = 0; w < workerCount; w++) {
                final int workerId = w;
                executor.execute(() -> {
                    try {
                        startLatch.await();
                        for (int r = 0; r < rounds; r++) {
                            if (workerId % 2 == 0) {
                                String reqId = "RACE-" + workerId + "-" + r;
                                CompletableFuture<TcpPacket> future = correlationManager.register(reqId, Duration.ofSeconds(2));
                                correlationManager.complete(reqId, TcpPacket.of("ACK", reqId, TcpPayload.of("OK")));
                                if (!future.isDone()) {
                                    future.cancel(false);
                                }
                            } else {
                                correlationManager.close();
                            }
                        }
                    } catch (Throwable ex) {
                        unexpectedErrors.add(ex);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertThat(doneLatch.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(unexpectedErrors).isEmpty();
            correlationManager.close();
            assertThat(correlationManager.getPendingCount()).isEqualTo(0);
        } finally {
            executor.close();
        }
    }
}
