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
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;

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
}
