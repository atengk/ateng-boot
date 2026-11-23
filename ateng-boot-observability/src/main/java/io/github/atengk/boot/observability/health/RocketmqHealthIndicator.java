package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import io.github.atengk.boot.rocketmq.listener.RocketmqConsumerContainer;
import io.github.atengk.boot.rocketmq.template.RocketmqTemplate;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * RocketMQ 5.x gRPC 瘦客户端安全健康探针
 * <p>
 * 探查 Producer 发送门面与 PushConsumer 消费监听容器活跃状态，
 * 继承 {@link AbstractSafeHealthIndicator} 实施异步超时切断与状态降级防卫。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class RocketmqHealthIndicator extends AbstractSafeHealthIndicator {

    private final ObjectProvider<RocketmqTemplate> templateProvider;
    private final ObjectProvider<RocketmqConsumerContainer> containerProvider;

    public RocketmqHealthIndicator(
            ObjectProvider<RocketmqTemplate> templateProvider,
            ObjectProvider<RocketmqConsumerContainer> containerProvider) {
        this(templateProvider, containerProvider, (Duration) null, null);
    }

    public RocketmqHealthIndicator(
            ObjectProvider<RocketmqTemplate> templateProvider,
            ObjectProvider<RocketmqConsumerContainer> containerProvider,
            ObservabilityProperties properties) {
        this(templateProvider, containerProvider, properties, null);
    }

    public RocketmqHealthIndicator(
            ObjectProvider<RocketmqTemplate> templateProvider,
            ObjectProvider<RocketmqConsumerContainer> containerProvider,
            ObservabilityProperties properties,
            Executor executor) {
        super(properties, executor);
        this.templateProvider = templateProvider;
        this.containerProvider = containerProvider;
    }

    public RocketmqHealthIndicator(
            ObjectProvider<RocketmqTemplate> templateProvider,
            ObjectProvider<RocketmqConsumerContainer> containerProvider,
            Duration timeout,
            Executor executor) {
        super(timeout, executor);
        this.templateProvider = templateProvider;
        this.containerProvider = containerProvider;
    }

    @Override
    protected String getComponentName() {
        return "rocketmq";
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        RocketmqTemplate template = templateProvider != null ? templateProvider.getIfAvailable() : null;
        RocketmqConsumerContainer container = containerProvider != null ? containerProvider.getIfAvailable() : null;

        if (template == null && container == null) {
            builder.unknown().withDetail("message", "未检测到已装配的 RocketMQ 客户端实例");
            return;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        boolean allHealthy = true;

        if (template != null) {
            Map<String, Object> producerDetails = new LinkedHashMap<>();
            Producer producer = template.getProducer();
            if (producer != null) {
                producerDetails.put("status", "UP");
                producerDetails.put("clientType", producer.getClass().getSimpleName());
            } else {
                producerDetails.put("status", "DOWN");
                producerDetails.put("error", "RocketmqTemplate.getProducer() 返回 null");
                allHealthy = false;
            }
            details.put("producer", producerDetails);
        }

        if (container != null) {
            Map<String, Object> consumerDetails = new LinkedHashMap<>();
            boolean running = container.isRunning();
            int activeConsumersCount = container.getActiveConsumers().size();
            int endpointsCount = container.getEndpoints().size();

            consumerDetails.put("status", running ? "UP" : "DOWN");
            consumerDetails.put("running", running);
            consumerDetails.put("activeConsumers", activeConsumersCount);
            consumerDetails.put("endpoints", endpointsCount);

            if (!running && endpointsCount > 0) {
                // 如果定义了消费端端点但容器未启动，判定为 DOWN
                allHealthy = false;
            }
            details.put("consumerContainer", consumerDetails);
        }

        if (allHealthy) {
            builder.up().withDetails(details);
        } else {
            builder.down().withDetails(details);
        }
    }
}
