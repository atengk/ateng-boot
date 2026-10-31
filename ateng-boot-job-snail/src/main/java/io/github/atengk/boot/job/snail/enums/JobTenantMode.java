package io.github.atengk.boot.job.snail.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * SnailJob 调度任务多租户隔离模式策略
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Getter
@AllArgsConstructor
public enum JobTenantMode {

    /**
     * 忽略租户过滤（平台级全局扫描，执行时不绑定或忽略当前租户隔离）
     */
    IGNORE("忽略租户限制"),

    /**
     * 参数绑定租户（从调度入参中动态解析 tenantId 并绑定至当前线程租户上下文）
     */
    PARAM("入参绑定租户"),

    /**
     * 全租户遍历执行（由多租户遍历模板负责流式在各个租户沙箱中依次执行）
     */
    ITERATE("多租户沙箱轮询");

    private final String description;
}
