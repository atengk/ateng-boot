package io.github.atengk.boot.observability.health;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KafkaSafeHealthIndicator 单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("KafkaSafeHealthIndicator Kafka 安全健康探针单元测试")
class KafkaSafeHealthIndicatorTest {

    @Test
    @DisplayName("未装配任何 Kafka 实例时，返回 UNKNOWN 状态")
    void testNoKafkaConfigured() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        KafkaSafeHealthIndicator indicator = new KafkaSafeHealthIndicator(
                beanFactory.getBeanProvider(KafkaTemplate.class),
                beanFactory.getBeanProvider(KafkaAdmin.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("message", "未检测到已装配的 Kafka 客户端实例");
    }

    @Test
    @DisplayName("Kafka 集群正常连通时，返回 UP 状态并包含 clusterId 与 nodeCount")
    void testKafkaUp() {
        AdminClient mockAdminClient = mock(AdminClient.class);
        DescribeClusterResult mockClusterResult = mock(DescribeClusterResult.class);

        KafkaFuture<String> clusterIdFuture = KafkaFuture.completedFuture("kafka-cluster-001");
        KafkaFuture<Collection<Node>> nodesFuture = KafkaFuture.completedFuture(List.of(
                new Node(1, "127.0.0.1", 9092),
                new Node(2, "127.0.0.1", 9093)
        ));

        when(mockAdminClient.describeCluster(any(DescribeClusterOptions.class))).thenReturn(mockClusterResult);
        when(mockClusterResult.clusterId()).thenReturn(clusterIdFuture);
        when(mockClusterResult.nodes()).thenReturn(nodesFuture);

        KafkaSafeHealthIndicator indicator = new KafkaSafeHealthIndicator(
                mockAdminClient,
                Duration.ofSeconds(1),
                null
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("clusterId", "kafka-cluster-001");
        assertThat(health.getDetails()).containsEntry("nodeCount", 2);
    }

    @Test
    @DisplayName("Kafka 集群不可达抛出异常时，返回 DOWN 状态")
    void testKafkaDown() {
        AdminClient mockAdminClient = mock(AdminClient.class);
        when(mockAdminClient.describeCluster(any(DescribeClusterOptions.class)))
                .thenThrow(new RuntimeException("Kafka Broker network unreachable"));

        KafkaSafeHealthIndicator indicator = new KafkaSafeHealthIndicator(
                mockAdminClient,
                Duration.ofSeconds(1),
                null
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("reason");
    }

    @Test
    @DisplayName("Kafka 探测超时熔断：切断降级为 UNKNOWN 状态")
    void testKafkaTimeoutDegradation() {
        AdminClient mockAdminClient = mock(AdminClient.class);
        when(mockAdminClient.describeCluster(any(DescribeClusterOptions.class))).thenAnswer(invocation -> {
            Thread.sleep(500);
            return mock(DescribeClusterResult.class);
        });

        KafkaSafeHealthIndicator indicator = new KafkaSafeHealthIndicator(
                mockAdminClient,
                Duration.ofMillis(80),
                null
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("status", "TIMEOUT");
    }

    @Test
    @DisplayName("确定性资源清理：调用 destroy 时安全关闭 AdminClient")
    void testDestroySafety() {
        AdminClient mockAdminClient = mock(AdminClient.class);
        KafkaSafeHealthIndicator indicator = new KafkaSafeHealthIndicator(
                mockAdminClient,
                Duration.ofSeconds(1),
                null
        );

        // 验证 destroy 不抛出异常
        indicator.destroy();
    }

    @Test
    @DisplayName("并发懒加载初始化 AdminClient（ReentrantLock 防 Pinning）：多并发安全无死锁且异常后锁可靠释放")
    void testConcurrentLazyInitializationAndLockReleaseOnFailure() throws Exception {
        ThreadPoolTaskExecutor probePool = new ThreadPoolTaskExecutor();
        probePool.setCorePoolSize(6);
        probePool.setMaxPoolSize(12);
        probePool.setThreadNamePrefix("kafka-probe-");
        probePool.initialize();

        ThreadPoolTaskExecutor callerPool = new ThreadPoolTaskExecutor();
        callerPool.setCorePoolSize(6);
        callerPool.setMaxPoolSize(6);
        callerPool.setThreadNamePrefix("kafka-caller-");
        callerPool.initialize();

        KafkaAdmin mockKafkaAdmin = mock(KafkaAdmin.class);
        AtomicInteger callCount = new AtomicInteger(0);
        when(mockKafkaAdmin.getConfigurationProperties()).thenAnswer(invocation -> {
            if (callCount.incrementAndGet() == 1) {
                throw new IllegalStateException("临时配置读取失败");
            }
            return Map.of();
        });

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("kafkaAdmin", mockKafkaAdmin);

        KafkaSafeHealthIndicator indicator = new KafkaSafeHealthIndicator(
                beanFactory.getBeanProvider(KafkaTemplate.class),
                beanFactory.getBeanProvider(KafkaAdmin.class),
                Duration.ofSeconds(1),
                probePool
        );

        try {
            // 1. 首次调用配置读取抛异常：应降级 DOWN 且 ReentrantLock 在 finally 释放，不阻塞后续探测
            Health first = indicator.health();
            assertThat(first.getStatus()).isEqualTo(Status.DOWN);

            // 2. 多线程并发调用探测与 destroy：无死锁、无异常泄漏
            int concurrency = 6;
            CountDownLatch startGate = new CountDownLatch(1);
            List<CompletableFuture<Health>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        startGate.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    return indicator.health();
                }, callerPool));
            }
            startGate.countDown();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
            for (CompletableFuture<Health> future : futures) {
                assertThat(future.get().getStatus()).isEqualTo(Status.UNKNOWN);
            }
        } finally {
            indicator.destroy();
            callerPool.destroy();
            probePool.destroy();
        }
    }
}
