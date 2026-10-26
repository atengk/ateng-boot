package io.github.atengk.boot.core.id;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 雪花 ID 逆向反解结构化不可变信息载体
 *
 * @param id           原始雪花长整型 ID
 * @param timestamp    发号毫秒时间戳（自 Unix Epoch 起）
 * @param instant      发号时间 Instant 实例
 * @param dateTime     发号时间 LocalDateTime（系统默认时区）
 * @param datacenterId 数据中心 ID (0~31)
 * @param workerId     工作节点 ID (0~31)
 * @param sequence     毫秒内序列号 (0~4095)
 * @param epoch        生成该 ID 所采用的基准时间戳（毫秒）
 * @author Ateng
 * @since 2026-10-26
 */
public record SnowflakeIdInfo(
        long id,
        long timestamp,
        Instant instant,
        LocalDateTime dateTime,
        long datacenterId,
        long workerId,
        long sequence,
        long epoch
) implements Serializable {
}
