package io.github.atengk.boot.kafka.builder;

import io.github.atengk.boot.kafka.constant.KafkaConstants;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 声明式 Kafka 主题与死信队列拓扑构建器
 *
 * <p>提供链式 DSL 声明式定义 Kafka 主题（指定分区数、副本因子、最小同步副本数、消息保留时间等），
 * 并支持通过 {@link #withDlt()} 一键附带生成标准死信主题（{@code *.DLT}），
 * 最终输出可被 Spring Kafka 自动创建的 {@link KafkaAdmin.NewTopics} 拓扑集合。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class KafkaTopicBuilder {

    private final String name;
    private int partitions = 3;
    private short replicas = 1;
    private final Map<String, String> configs = new HashMap<>();

    private boolean dltEnabled = false;
    private String dltSuffix = KafkaConstants.DEFAULT_DLT_SUFFIX;
    private Integer dltPartitions;
    private Short dltReplicas;
    private final Map<String, String> dltConfigs = new HashMap<>();

    private KafkaTopicBuilder(String name) {
        this.name = Objects.requireNonNull(name, "Topic 名称不能为 null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Topic 名称不能为空白字符");
        }
    }

    /**
     * 创建指定名称的主题构建器
     *
     * @param name 主题名称
     * @return 构建器实例
     */
    public static KafkaTopicBuilder topic(String name) {
        return new KafkaTopicBuilder(name);
    }

    /**
     * 设置分区数（默认 3）
     */
    public KafkaTopicBuilder partitions(int partitions) {
        if (partitions <= 0) {
            throw new IllegalArgumentException("分区数必须大于 0");
        }
        this.partitions = partitions;
        return this;
    }

    /**
     * 设置副本因子（默认 1）
     */
    public KafkaTopicBuilder replicas(int replicas) {
        if (replicas <= 0) {
            throw new IllegalArgumentException("副本数必须大于 0");
        }
        this.replicas = (short) replicas;
        return this;
    }

    /**
     * 设置最小同步副本数 min.insync.replicas
     */
    public KafkaTopicBuilder minInSyncReplicas(int minInSyncReplicas) {
        if (minInSyncReplicas <= 0) {
            throw new IllegalArgumentException("最小同步副本数必须大于 0");
        }
        this.configs.put(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(minInSyncReplicas));
        return this;
    }

    /**
     * 设置消息保留时长 retention.ms
     */
    public KafkaTopicBuilder retention(Duration retention) {
        if (retention != null) {
            this.configs.put(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(retention.toMillis()));
        }
        return this;
    }

    /**
     * 设置日志分段容量 segment.bytes
     */
    public KafkaTopicBuilder segmentSize(DataSize segmentSize) {
        if (segmentSize != null) {
            this.configs.put(TopicConfig.SEGMENT_BYTES_CONFIG, String.valueOf(segmentSize.toBytes()));
        }
        return this;
    }

    /**
     * 设置通用底层主题配置参数
     */
    public KafkaTopicBuilder config(String key, String value) {
        if (key != null && value != null) {
            this.configs.put(key, value);
        }
        return this;
    }

    /**
     * 一键附带声明默认死信主题（后缀为 {@code .DLT}，分区与副本默认继承主主题）
     */
    public KafkaTopicBuilder withDlt() {
        this.dltEnabled = true;
        return this;
    }

    /**
     * 一键附带声明指定后缀的死信主题
     */
    public KafkaTopicBuilder withDlt(String dltSuffix) {
        this.dltEnabled = true;
        if (dltSuffix != null && !dltSuffix.isBlank()) {
            this.dltSuffix = dltSuffix;
        }
        return this;
    }

    /**
     * 附带声明死信主题并支持定制死信分区与配置
     */
    public KafkaTopicBuilder withDlt(Consumer<DltConfigBuilder> customizer) {
        this.dltEnabled = true;
        if (customizer != null) {
            DltConfigBuilder builder = new DltConfigBuilder();
            customizer.accept(builder);
            if (builder.dltSuffix != null) {
                this.dltSuffix = builder.dltSuffix;
            }
            if (builder.partitions != null) {
                this.dltPartitions = builder.partitions;
            }
            if (builder.replicas != null) {
                this.dltReplicas = builder.replicas;
            }
            this.dltConfigs.putAll(builder.configs);
        }
        return this;
    }

    /**
     * 关闭死信主题附带声明
     */
    public KafkaTopicBuilder withoutDlt() {
        this.dltEnabled = false;
        return this;
    }

    /**
     * 构建主主题 NewTopic 实例
     */
    public NewTopic buildMain() {
        NewTopic topic = new NewTopic(this.name, this.partitions, this.replicas);
        if (!this.configs.isEmpty()) {
            topic.configs(this.configs);
        }
        return topic;
    }

    /**
     * 构建配套死信主题 NewTopic 实例（若未开启 withDlt 则返回 null）
     */
    public NewTopic buildDlt() {
        if (!this.dltEnabled) {
            return null;
        }
        int dltP = (this.dltPartitions != null) ? this.dltPartitions : this.partitions;
        short dltR = (this.dltReplicas != null) ? this.dltReplicas : this.replicas;
        NewTopic dltTopic = new NewTopic(this.name + this.dltSuffix, dltP, dltR);

        Map<String, String> mergedConfigs = new HashMap<>(this.configs);
        mergedConfigs.putAll(this.dltConfigs);
        if (!mergedConfigs.isEmpty()) {
            dltTopic.configs(mergedConfigs);
        }
        return dltTopic;
    }

    /**
     * 构建包含主主题与死信主题的 NewTopic 列表
     */
    public List<NewTopic> buildList() {
        List<NewTopic> list = new ArrayList<>();
        list.add(buildMain());
        if (this.dltEnabled) {
            list.add(buildDlt());
        }
        return list;
    }

    /**
     * 构建 Spring Kafka 纳管的 NewTopics 拓扑集合对象
     */
    public KafkaAdmin.NewTopics build() {
        List<NewTopic> list = buildList();
        return new KafkaAdmin.NewTopics(list.toArray(new NewTopic[0]));
    }

    /**
     * 死信主题参数定制器
     */
    public static class DltConfigBuilder {
        private String dltSuffix;
        private Integer partitions;
        private Short replicas;
        private final Map<String, String> configs = new HashMap<>();

        public DltConfigBuilder suffix(String suffix) {
            this.dltSuffix = suffix;
            return this;
        }

        public DltConfigBuilder partitions(int partitions) {
            this.partitions = partitions;
            return this;
        }

        public DltConfigBuilder replicas(int replicas) {
            this.replicas = (short) replicas;
            return this;
        }

        public DltConfigBuilder retention(Duration retention) {
            if (retention != null) {
                this.configs.put(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(retention.toMillis()));
            }
            return this;
        }

        public DltConfigBuilder config(String key, String value) {
            if (key != null && value != null) {
                this.configs.put(key, value);
            }
            return this;
        }
    }
}
