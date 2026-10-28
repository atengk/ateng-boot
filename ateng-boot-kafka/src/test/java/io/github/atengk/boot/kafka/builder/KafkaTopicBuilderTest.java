package io.github.atengk.boot.kafka.builder;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KafkaTopicBuilder 拓扑构建器单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("KafkaTopicBuilder 声明式拓扑构建器测试")
class KafkaTopicBuilderTest {

    @Test
    @DisplayName("默认参数构建单个主题（默认 3 分区，1 副本）")
    void testDefaultTopicBuild() {
        NewTopic topic = KafkaTopicBuilder.topic("order.create.topic").buildMain();

        assertThat(topic.name()).isEqualTo("order.create.topic");
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }

    @Test
    @DisplayName("链式定制分区、副本、最小同步副本、保留时长与分段容量")
    void testCustomTopicBuild() {
        NewTopic topic = KafkaTopicBuilder.topic("audit.log.topic")
                .partitions(6)
                .replicas(2)
                .minInSyncReplicas(2)
                .retention(Duration.ofDays(7))
                .segmentSize(DataSize.ofMegabytes(512))
                .config("cleanup.policy", "compact")
                .buildMain();

        assertThat(topic.name()).isEqualTo("audit.log.topic");
        assertThat(topic.numPartitions()).isEqualTo(6);
        assertThat(topic.replicationFactor()).isEqualTo((short) 2);

        assertThat(topic.configs().get(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG)).isEqualTo("2");
        assertThat(topic.configs().get(TopicConfig.RETENTION_MS_CONFIG)).isEqualTo(String.valueOf(Duration.ofDays(7).toMillis()));
        assertThat(topic.configs().get(TopicConfig.SEGMENT_BYTES_CONFIG)).isEqualTo(String.valueOf(DataSize.ofMegabytes(512).toBytes()));
        assertThat(topic.configs().get("cleanup.policy")).isEqualTo("compact");
    }

    @Test
    @DisplayName("使用 withDlt 一键附带生成 .DLT 死信主题")
    void testWithDlt() {
        List<NewTopic> topics = KafkaTopicBuilder.topic("payment.topic")
                .partitions(4)
                .replicas(2)
                .withDlt()
                .buildList();

        assertThat(topics).hasSize(2);
        NewTopic mainTopic = topics.get(0);
        NewTopic dltTopic = topics.get(1);

        assertThat(mainTopic.name()).isEqualTo("payment.topic");
        assertThat(mainTopic.numPartitions()).isEqualTo(4);

        assertThat(dltTopic.name()).isEqualTo("payment.topic.DLT");
        // DLT 默认继承主主题的分区与副本
        assertThat(dltTopic.numPartitions()).isEqualTo(4);
        assertThat(dltTopic.replicationFactor()).isEqualTo((short) 2);
    }

    @Test
    @DisplayName("使用 withDlt 自定义后缀与独立死信参数")
    void testWithCustomDlt() throws Exception {
        var builder = KafkaTopicBuilder.topic("notification.topic")
                .partitions(8)
                .replicas(1)
                .retention(Duration.ofDays(3))
                .withDlt(dlt -> dlt.suffix(".DEAD-LETTER")
                        .partitions(2)
                        .retention(Duration.ofDays(30)));

        KafkaAdmin.NewTopics newTopics = builder.build();
        assertThat(newTopics).isNotNull();

        java.lang.reflect.Method method = KafkaAdmin.NewTopics.class.getDeclaredMethod("getNewTopics");
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Collection<NewTopic> extracted = (java.util.Collection<NewTopic>) method.invoke(newTopics);
        assertThat(extracted).hasSize(2);

        List<NewTopic> list = builder.buildList();
        assertThat(list).hasSize(2);

        NewTopic dltTopic = list.stream()
                .filter(t -> t.name().endsWith(".DEAD-LETTER"))
                .findFirst()
                .orElseThrow();

        assertThat(dltTopic.name()).isEqualTo("notification.topic.DEAD-LETTER");
        assertThat(dltTopic.numPartitions()).isEqualTo(2);
        assertThat(dltTopic.configs().get(TopicConfig.RETENTION_MS_CONFIG))
                .isEqualTo(String.valueOf(Duration.ofDays(30).toMillis()));
    }

    @Test
    @DisplayName("合法性卫语句校验防卫")
    void testValidation() {
        assertThatThrownBy(() -> KafkaTopicBuilder.topic(null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> KafkaTopicBuilder.topic("   "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> KafkaTopicBuilder.topic("test").partitions(0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> KafkaTopicBuilder.topic("test").replicas(-1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> KafkaTopicBuilder.topic("test").minInSyncReplicas(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
