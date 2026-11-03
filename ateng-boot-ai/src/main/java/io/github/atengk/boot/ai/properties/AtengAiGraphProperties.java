package io.github.atengk.boot.ai.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * 认知状态图与人机协作配置属性类
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.ai.graph")
public class AtengAiGraphProperties {

    /**
     * 是否启用 AI 认知图底座（默认启用）
     */
    private boolean enabled = true;

    /**
     * 检查点与人机协作存储配置
     */
    @NestedConfigurationProperty
    private CheckpointProperties checkpoint = new CheckpointProperties();

    /**
     * 检查点配置明细
     */
    @Data
    public static class CheckpointProperties {

        /**
         * 检查点存储引擎类型（默认 AUTO 自动探测）
         */
        private CheckpointStoreType type = CheckpointStoreType.AUTO;

        /**
         * 检查点 Redis 键前缀
         */
        private String keyPrefix = "ateng:ai:graph:checkpoint:";

        /**
         * 人机协作分布式租约锁 Redis 键前缀
         */
        private String lockPrefix = "ateng:ai:graph:lock:";

        /**
         * 人机协作分布式租约锁默认持有超时时间（默认 30 秒）
         */
        private Duration lockTtl = Duration.ofSeconds(30);

        /**
         * 检查点快照默认持久化保留时长与淘汰 TTL（默认 7 天）
         */
        private Duration checkpointTtl = Duration.ofDays(7);
    }
}
