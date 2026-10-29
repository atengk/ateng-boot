package io.github.atengk.boot.kafka.builder;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KafkaTopicBuilder 日志压实与高级拓扑配置扩展测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("KafkaTopicBuilder 日志压实与高级拓扑配置扩展测试")
class KafkaTopicBuilderExtendedTest {

    @Test
    @DisplayName("compact: 开启日志压实策略 (cleanup.policy=compact)")
    void testCompactPolicy() {
        NewTopic topic = KafkaTopicBuilder.topic("user.state.snapshot")
                .partitions(3)
                .compact()
                .buildMain();

        assertThat(topic.name()).isEqualTo("user.state.snapshot");
        assertThat(topic.configs())
                .containsEntry(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT);
    }

    @Test
    @DisplayName("deleteAndCompact: 开启保留删除与日志压实双重策略 (cleanup.policy=delete,compact)")
    void testDeleteAndCompactPolicy() {
        NewTopic topic = KafkaTopicBuilder.topic("order.state.stream")
                .partitions(6)
                .deleteAndCompact()
                .buildMain();

        assertThat(topic.name()).isEqualTo("order.state.stream");
        assertThat(topic.configs())
                .containsEntry(TopicConfig.CLEANUP_POLICY_CONFIG, "delete,compact");
    }

    @Test
    @DisplayName("maxMessageBytes: 强类型配置单条消息最大体积限制 max.message.bytes")
    void testMaxMessageBytes() {
        NewTopic topic = KafkaTopicBuilder.topic("telemetry.large.payload")
                .partitions(4)
                .maxMessageBytes(DataSize.ofMegabytes(10))
                .buildMain();

        assertThat(topic.configs())
                .containsEntry(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, String.valueOf(DataSize.ofMegabytes(10).toBytes()));
    }

    @Test
    @DisplayName("compressionType: 配置主题端压缩算法覆盖")
    void testCompressionType() {
        NewTopic topic = KafkaTopicBuilder.topic("archive.compressed.topic")
                .partitions(2)
                .compressionType("zstd")
                .buildMain();

        assertThat(topic.configs())
                .containsEntry(TopicConfig.COMPRESSION_TYPE_CONFIG, "zstd");
    }

    @Test
    @DisplayName("组合配置：压实、报文体积、压缩类型与死信主题继承")
    void testCombinedAdvancedTopologyWithDlt() {
        List<NewTopic> topics = KafkaTopicBuilder.topic("config.broadcast.topic")
                .partitions(5)
                .replicas(2)
                .compact()
                .maxMessageBytes(DataSize.ofMegabytes(5))
                .compressionType("lz4")
                .withDlt(dlt -> dlt.suffix(".DLT")
                        .deleteAndCompact()
                        .maxMessageBytes(DataSize.ofMegabytes(8))
                        .compressionType("gzip"))
                .buildList();

        assertThat(topics).hasSize(2);
        NewTopic mainTopic = topics.get(0);
        NewTopic dltTopic = topics.get(1);

        assertThat(mainTopic.configs())
                .containsEntry(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT)
                .containsEntry(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, String.valueOf(DataSize.ofMegabytes(5).toBytes()))
                .containsEntry(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        assertThat(dltTopic.configs())
                .containsEntry(TopicConfig.CLEANUP_POLICY_CONFIG, "delete,compact")
                .containsEntry(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, String.valueOf(DataSize.ofMegabytes(8).toBytes()))
                .containsEntry(TopicConfig.COMPRESSION_TYPE_CONFIG, "gzip");
    }

    @Test
    @DisplayName("防御性边界测试：null 与空白参数防穿透")
    void testDefensiveNullAndBlankInputs() {
        NewTopic topic = KafkaTopicBuilder.topic("defensive.topic")
                .maxMessageBytes(null)
                .compressionType(null)
                .compressionType("   ")
                .buildMain();

        // 当未设置有效配置时，NewTopic 的 configs 为 null 或为空
        if (topic.configs() != null) {
            assertThat(topic.configs())
                    .doesNotContainKey(TopicConfig.MAX_MESSAGE_BYTES_CONFIG)
                    .doesNotContainKey(TopicConfig.COMPRESSION_TYPE_CONFIG);
        } else {
            assertThat(topic.configs()).isNull();
        }
    }
}
