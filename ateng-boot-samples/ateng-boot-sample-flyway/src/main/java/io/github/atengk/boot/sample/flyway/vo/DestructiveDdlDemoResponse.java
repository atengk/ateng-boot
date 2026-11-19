package io.github.atengk.boot.sample.flyway.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 待执行 SQL 高危破坏性 DDL 静态预检演练响应视图对象
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DestructiveDdlDemoResponse {

    /**
     * 演练使用的临时迁移脚本文件名
     */
    private String scriptName;

    /**
     * 是否处于强制高危 DDL 拦截策略下
     */
    private boolean guardEnforced;

    /**
     * 本次演练是否允许显式豁免注释
     */
    private boolean allowExemptionAnnotation;

    /**
     * 预检是否放行通过并完成隔离执行
     */
    private boolean passed;

    /**
     * 实际执行的迁移脚本数
     */
    private int executedMigrations;

    /**
     * 演练结果说明
     */
    private String summary;
}
