package io.github.atengk.boot.storage.properties;

import lombok.Data;

import java.time.Duration;

/**
 * 孤儿分片碎片生命周期治理与后台清理配置属性
 * 针对客户端意外中断上传遗留的分片碎片，提供受管定时清理能力
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Data
public class ChunkCleanerProperties {

    /**
     * 是否启用后台孤儿分片碎片定时清理守护任务（默认 true 开启）
     */
    private boolean enabled = true;

    /**
     * 后台分片清理任务扫描执行周期（默认 2 小时）
     */
    private Duration interval = Duration.ofHours(2);

    /**
     * 分片碎片最大存活保留寿命（默认 24 小时，超出此寿命视为孤儿碎片执行物理清理）
     */
    private Duration maxAge = Duration.ofHours(24);
}
