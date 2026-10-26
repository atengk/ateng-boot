package io.github.atengk.boot.tcp.correlation;

import io.github.atengk.boot.tcp.exception.TcpException;
import io.github.atengk.boot.tcp.exception.TcpTimeoutException;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

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
}
