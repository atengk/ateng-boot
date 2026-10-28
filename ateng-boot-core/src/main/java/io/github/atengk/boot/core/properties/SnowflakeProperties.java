package io.github.atengk.boot.core.properties;

import io.github.atengk.boot.core.id.Snowflake;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

/**
 * Twitter Snowflake 雪花发号器自动装配与运行时配置属性
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Getter
@Setter
public class SnowflakeProperties {

    /**
     * 是否开启 Snowflake 雪花发号器自动装配 Bean
     */
    private boolean enabled = true;

    /**
     * 工作节点 ID（0~31，未配置时自动从环境变量、网卡 MAC 或 IP 自适应推导）
     */
    private Long workerId;

    /**
     * 数据中心 ID（0~31，未配置时自动从环境变量、网卡 MAC 或 IP 自适应推导）
     */
    private Long datacenterId;

    /**
     * 基准时间戳纪元（毫秒，默认 1767225600000L - 2026-01-01 00:00:00 UTC）
     */
    private long epoch = Snowflake.DEFAULT_EPOCH;

    /**
     * 最大允许时钟回拨容忍自旋时长（未配置时沿用顶层 clockBackoffTolerance，默认 5ms）
     */
    private Duration clockBackoffTolerance;
}
