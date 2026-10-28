package io.github.atengk.boot.excel.properties;

import lombok.Data;

import java.time.Duration;

/**
 * Excel 异步导出 Redis 分布式任务中心配置属性
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Data
public class ExcelAsyncExportRedisProperties {

    /**
     * 是否启用基于 Redis 的分布式异步导出任务中心（默认 true，当容器内存在 Redis 时自适应生效）
     */
    private boolean enabled = true;

    /**
     * 任务在 Redis 中的键前缀（默认 "ateng:excel:async-task:"）
     */
    private String keyPrefix = "ateng:excel:async-task:";

    /**
     * 任务元数据在 Redis 中的保留与有效时长（默认 24h）
     */
    private Duration ttl = Duration.ofHours(24);
}
