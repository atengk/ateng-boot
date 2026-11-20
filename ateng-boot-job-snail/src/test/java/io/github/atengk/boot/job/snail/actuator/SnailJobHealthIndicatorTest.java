package io.github.atengk.boot.job.snail.actuator;

import io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link SnailJobHealthIndicator} 健康探针单元测试
 *
 * <p>基于本地回环 {@link ServerSocket} 验证非阻塞缓存、受管并发探测、多节点部分失联降级 UP
 * 与全失联 DOWN 语义，彻底摆脱对底层私有字段的反射耦合。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailJobHealthIndicatorTest {

    private SnailJobProperties properties;
    private GracefulDrainStateMachine drainStateMachine;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    public void setUp() {
        properties = new SnailJobProperties();
        properties.setEnabled(true);
        properties.setGroup("test-group");
        properties.setNamespace("test-namespace");
        properties.setPort(17889);
        properties.setHealthCacheTtl(Duration.ofSeconds(10));
        properties.setHealthProbeTimeout(Duration.ofMillis(300));

        drainStateMachine = new GracefulDrainStateMachine(properties);

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setThreadNamePrefix("health-probe-test-");
        executor.initialize();
    }

    @AfterEach
    public void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("执行器禁用时返回 UP(DISABLED)")
    public void testHealthWhenDisabled() {
        properties.setEnabled(false);
        SnailJobHealthIndicator indicator = new SnailJobHealthIndicator(properties, drainStateMachine, executor);
        Health health = indicator.health();
        assertNotNull(health);
        assertEquals(Status.UP, health.getStatus());
        assertEquals("DISABLED", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("未配置服务端地址时返回 UNKNOWN(BYPASSED)")
    public void testHealthWhenBypassed() {
        properties.setServer("");
        SnailJobHealthIndicator indicator = new SnailJobHealthIndicator(properties, drainStateMachine, executor);
        Health health = indicator.health();
        assertNotNull(health);
        assertEquals(Status.UNKNOWN, health.getStatus());
        assertEquals("BYPASSED", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("全部服务端节点不可达时返回 DOWN 且列明离线清单")
    public void testHealthDownWhenAllServersUnreachable() throws IOException {
        int deadPort = allocateUnusedPort();
        properties.setServer("127.0.0.1:" + deadPort);
        SnailJobHealthIndicator indicator = new SnailJobHealthIndicator(properties, drainStateMachine, executor);

        Health health = indicator.health();
        assertNotNull(health);
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(false, health.getDetails().get("serverReachable"));
        assertEquals(false, health.getDetails().get("degraded"));
        assertEquals("test-group", health.getDetails().get("group"));
        assertEquals("test-namespace", health.getDetails().get("namespace"));
        @SuppressWarnings("unchecked")
        List<String> offline = (List<String>) health.getDetails().get("offlineServers");
        assertThat(offline).containsExactly("127.0.0.1:" + deadPort);
    }

    @Test
    @DisplayName("多节点 1 通 1 断时返回 UP 并标记 degraded 与在线/离线清单")
    public void testHealthMultiServerPartialReachableReturnsUpWithDegradedDetail() throws IOException {
        int deadPort = allocateUnusedPort();
        try (ServerSocket liveSocket = new ServerSocket(0)) {
            int livePort = liveSocket.getLocalPort();
            properties.setServer("127.0.0.1:" + livePort + ", 127.0.0.1:" + deadPort);
            SnailJobHealthIndicator indicator = new SnailJobHealthIndicator(properties, drainStateMachine, executor);

            Health health = indicator.health();
            assertEquals(Status.UP, health.getStatus());
            assertEquals(true, health.getDetails().get("serverReachable"));
            assertEquals(true, health.getDetails().get("degraded"));

            @SuppressWarnings("unchecked")
            List<String> online = (List<String>) health.getDetails().get("onlineServers");
            @SuppressWarnings("unchecked")
            List<String> offline = (List<String>) health.getDetails().get("offlineServers");
            assertThat(online).containsExactly("127.0.0.1:" + livePort);
            assertThat(offline).containsExactly("127.0.0.1:" + deadPort);
        }
    }

    @Test
    @DisplayName("healthCacheTtl 缓存命中与设为 0s 实时穿透禁用语义")
    public void testHealthCacheTtlHitAndZeroDisable() throws IOException {
        // 1. 启用 10s 缓存：关闭端口后第二次探测应命中缓存继续返回 UP 且 cacheHit=true
        ServerSocket tempSocket = new ServerSocket(0);
        int port2 = tempSocket.getLocalPort();
        SnailJobProperties ttlProps = new SnailJobProperties();
        ttlProps.setEnabled(true);
        ttlProps.setServer("127.0.0.1:" + port2);
        ttlProps.setHealthCacheTtl(Duration.ofSeconds(10));
        ttlProps.setHealthProbeTimeout(Duration.ofMillis(300));
        SnailJobHealthIndicator ttlIndicator = new SnailJobHealthIndicator(ttlProps, drainStateMachine, executor);
        assertEquals(Status.UP, ttlIndicator.health().getStatus());
        tempSocket.close();

        Health cachedSecond = ttlIndicator.health();
        assertEquals(Status.UP, cachedSecond.getStatus());
        assertEquals(true, cachedSecond.getDetails().get("cacheHit"));

        // 2. healthCacheTtl 设为 0s（禁用缓存）：关闭端口后立即实时探测为 DOWN
        try (ServerSocket liveNoCache = new ServerSocket(0)) {
            int port3 = liveNoCache.getLocalPort();
            SnailJobProperties zeroTtlProps = new SnailJobProperties();
            zeroTtlProps.setEnabled(true);
            zeroTtlProps.setServer("127.0.0.1:" + port3);
            zeroTtlProps.setHealthCacheTtl(Duration.ZERO);
            zeroTtlProps.setHealthProbeTimeout(Duration.ofMillis(300));
            SnailJobHealthIndicator noCacheIndicator = new SnailJobHealthIndicator(zeroTtlProps, drainStateMachine, executor);

            Health h1 = noCacheIndicator.health();
            assertEquals(Status.UP, h1.getStatus());
            assertEquals(false, h1.getDetails().get("cacheHit"));
            liveNoCache.close();

            Health h2 = noCacheIndicator.health();
            assertEquals(Status.DOWN, h2.getStatus());
            assertEquals(false, h2.getDetails().get("cacheHit"));
        }
    }

    @Test
    @DisplayName("健康探针实时暴露平滑排空与在途计数状态（缓存命中期间亦实时刷新排空态）")
    public void testHealthReflectsDrainingState() throws IOException {
        try (ServerSocket liveSocket = new ServerSocket(0)) {
            properties.setServer("127.0.0.1:" + liveSocket.getLocalPort());
            SnailJobHealthIndicator indicator = new SnailJobHealthIndicator(properties, drainStateMachine, executor);
            assertEquals(Status.UP, indicator.health().getStatus());

            drainStateMachine.incrementInFlightJob();
            drainStateMachine.incrementInFlightRetry();
            drainStateMachine.setDraining(true);

            Health health = indicator.health();
            assertEquals(Status.UP, health.getStatus());
            assertEquals(true, health.getDetails().get("draining"));
            assertEquals(1, health.getDetails().get("inFlightJobs"));
            assertEquals(1, health.getDetails().get("inFlightRetries"));
        }
    }

    private static int allocateUnusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
