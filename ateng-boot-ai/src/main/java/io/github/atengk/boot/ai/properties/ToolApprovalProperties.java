package io.github.atengk.boot.ai.properties;

import lombok.Data;

import java.time.Duration;

/**
 * 高危工具人机协同审批治理配置属性
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
public class ToolApprovalProperties {

    /**
     * 是否启用高危工具人机协作审批机制
     */
    private boolean enabled = true;

    /**
     * 工单快照默认过期时间
     */
    private Duration defaultTimeout = Duration.ofMinutes(30);

    /**
     * Redis 审批工单缓存键前缀
     */
    private String keyPrefix = "ateng:ai:tool-approval:";

    /**
     * 存储实现类型: AUTO / REDIS / IN_MEMORY
     */
    private StorageType type = StorageType.AUTO;

    public enum StorageType {
        AUTO,
        REDIS,
        IN_MEMORY
    }
}
