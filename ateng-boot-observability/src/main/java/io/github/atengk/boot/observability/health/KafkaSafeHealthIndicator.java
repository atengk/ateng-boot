package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Kafka 集群连通性与生产消费受管安全健康探针
 * <p>
 * 探查 Kafka Broker 节点拓扑与集群可用性（{@code describeCluster}），
 * 继承 {@link AbstractSafeHealthIndicator} 实施异步超时切断与状态降级防卫，
 * 并实现 {@link DisposableBean} 保障底层 AdminClient 确定性资源释放。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Slf4j
public class KafkaSafeHealthIndicator extends AbstractSafeHealthIndicator implements DisposableBean {

    @SuppressWarnings("rawtypes")
    private final ObjectProvider<KafkaTemplate> kafkaTemplateProvider;
    private final ObjectProvider<KafkaAdmin> kafkaAdminProvider;
    private final AdminClient injectedAdminClient;
    private volatile AdminClient internalAdminClient;

    @SuppressWarnings("rawtypes")
    public KafkaSafeHealthIndicator(
            ObjectProvider<KafkaTemplate> kafkaTemplateProvider,
            ObjectProvider<KafkaAdmin> kafkaAdminProvider) {
        this(kafkaTemplateProvider, kafkaAdminProvider, (Duration) null, null);
    }

    @SuppressWarnings("rawtypes")
    public KafkaSafeHealthIndicator(
            ObjectProvider<KafkaTemplate> kafkaTemplateProvider,
            ObjectProvider<KafkaAdmin> kafkaAdminProvider,
            ObservabilityProperties properties) {
        this(kafkaTemplateProvider, kafkaAdminProvider, properties, null);
    }

    @SuppressWarnings("rawtypes")
    public KafkaSafeHealthIndicator(
            ObjectProvider<KafkaTemplate> kafkaTemplateProvider,
            ObjectProvider<KafkaAdmin> kafkaAdminProvider,
            ObservabilityProperties properties,
            Executor executor) {
        super(properties, executor);
        this.kafkaTemplateProvider = kafkaTemplateProvider;
        this.kafkaAdminProvider = kafkaAdminProvider;
        this.injectedAdminClient = null;
    }

    @SuppressWarnings("rawtypes")
    public KafkaSafeHealthIndicator(
            ObjectProvider<KafkaTemplate> kafkaTemplateProvider,
            ObjectProvider<KafkaAdmin> kafkaAdminProvider,
            Duration timeout,
            Executor executor) {
        super(timeout, executor);
        this.kafkaTemplateProvider = kafkaTemplateProvider;
        this.kafkaAdminProvider = kafkaAdminProvider;
        this.injectedAdminClient = null;
    }

    /**
     * 测试或显式注入专用构造器
     *
     * @param adminClient 显式注入的 AdminClient 实例
     * @param timeout     超时阈值
     * @param executor    受管线程池
     */
    public KafkaSafeHealthIndicator(AdminClient adminClient, Duration timeout, Executor executor) {
        super(timeout, executor);
        this.kafkaTemplateProvider = null;
        this.kafkaAdminProvider = null;
        this.injectedAdminClient = adminClient;
    }

    @Override
    protected String getComponentName() {
        return "kafka";
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        AdminClient client = resolveAdminClient();
        if (client == null) {
            builder.unknown().withDetail("message", "未检测到已装配的 Kafka 客户端实例");
            return;
        }

        int timeoutMs = (int) getTimeout().toMillis();
        DescribeClusterOptions options = new DescribeClusterOptions().timeoutMs(timeoutMs);
        DescribeClusterResult clusterResult = client.describeCluster(options);

        String clusterId = clusterResult.clusterId().get(timeoutMs, TimeUnit.MILLISECONDS);
        int nodeCount = clusterResult.nodes().get(timeoutMs, TimeUnit.MILLISECONDS).size();

        builder.up()
                .withDetail("clusterId", clusterId)
                .withDetail("nodeCount", nodeCount);
    }

    private AdminClient resolveAdminClient() {
        if (this.injectedAdminClient != null) {
            return this.injectedAdminClient;
        }
        if (this.internalAdminClient == null) {
            synchronized (this) {
                if (this.internalAdminClient == null) {
                    Map<String, Object> configs = null;
                    if (kafkaAdminProvider != null && kafkaAdminProvider.getIfAvailable() != null) {
                        configs = kafkaAdminProvider.getIfAvailable().getConfigurationProperties();
                    } else if (kafkaTemplateProvider != null && kafkaTemplateProvider.getIfAvailable() != null) {
                        configs = kafkaTemplateProvider.getIfAvailable().getProducerFactory().getConfigurationProperties();
                    }
                    if (configs != null && !configs.isEmpty()) {
                        this.internalAdminClient = AdminClient.create(configs);
                    }
                }
            }
        }
        return this.internalAdminClient;
    }

    @Override
    public void destroy() {
        if (this.internalAdminClient != null) {
            try {
                this.internalAdminClient.close(Duration.ofSeconds(1));
            } catch (Exception ex) {
                log.warn("关闭 Kafka AdminClient 异常: {}", ex.getMessage());
            }
        }
    }
}
