package io.github.atengk.boot.sample.kafka.offline;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 离线内存模拟 {@link KafkaAdmin}，在容器启动及运行时将主题声明与描述操作收敛至 {@link OfflineKafkaBroker}，避免连接真实 Kafka 集群
 *
 * @author Ateng
 * @since 2026-11-15
 */
public class OfflineKafkaAdmin extends KafkaAdmin {

    private static final String OFFLINE_CLUSTER_ID = "offline-kafka-cluster";

    private final OfflineKafkaBroker broker;

    public OfflineKafkaAdmin(Map<String, Object> config, OfflineKafkaBroker broker) {
        super(config != null ? config : Collections.emptyMap());
        this.broker = Objects.requireNonNull(broker, "broker 不能为空");
    }

    @Override
    public void afterSingletonsInstantiated() {
        Collection<NewTopic> declared = newTopics();
        if (declared != null && !declared.isEmpty()) {
            broker.declareTopics(declared);
        }
    }

    @Override
    public String clusterId() {
        return OFFLINE_CLUSTER_ID;
    }

    @Override
    public void createOrModifyTopics(NewTopic... topics) {
        if (topics == null || topics.length == 0) {
            return;
        }
        broker.declareTopics(Arrays.asList(topics));
    }

    @Override
    public Map<String, TopicDescription> describeTopics(String... topicNames) {
        if (topicNames == null || topicNames.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, KafkaFuture<TopicDescription>> futures = broker.describeTopics(Arrays.asList(topicNames));
        Map<String, TopicDescription> result = new LinkedHashMap<>(futures.size());
        for (Map.Entry<String, KafkaFuture<TopicDescription>> entry : futures.entrySet()) {
            try {
                result.put(entry.getKey(), entry.getValue().get());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new KafkaException("离线描述主题被中断: " + entry.getKey(), ex);
            } catch (Exception ex) {
                throw new KafkaException("离线描述主题失败: " + entry.getKey(), ex);
            }
        }
        return result;
    }
}
