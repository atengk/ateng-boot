package io.github.atengk.boot.sample.liteflow.model;

import java.io.Serializable;
import java.util.List;

/**
 * 全局执行拦截器审计统计响应模型
 *
 * @author Ateng
 * @since 2026-11-21
 */
public record InterceptorStatsResponse(
        long successCount,
        long errorCount,
        List<String> recentRecords
) implements Serializable {
}
