package io.github.atengk.boot.search.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SearchMetrics 单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
class SearchMetricsTest {

    @Test
    @DisplayName("验证 Micrometer Timer 正常采集 query 与 bulk 操作度量指标")
    void testMetricsRecording() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SearchMetrics metrics = new SearchMetrics(registry);

        assertThat(metrics.getQueryTimer()).isNotNull();
        assertThat(metrics.getBulkTimer()).isNotNull();

        String queryResult = metrics.recordQuery(() -> "query-ok");
        assertThat(queryResult).isEqualTo("query-ok");
        assertThat(metrics.getQueryTimer().count()).isEqualTo(1L);

        metrics.recordBulk(() -> {
            // do bulk
        });
        assertThat(metrics.getBulkTimer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("验证低基数操作标签度量 ateng.search.operation.time 在成功与异常分支准确采集")
    void testLowCardinalityOperationMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SearchMetrics metrics = new SearchMetrics(registry);

        String res = metrics.recordOperation("search", false, () -> "ok");
        assertThat(res).isEqualTo("ok");
        assertThat(metrics.getQueryTimer().count()).isEqualTo(1L);
        assertThat(registry.get("ateng.search.operation.time")
                .tag("operation", "search")
                .tag("outcome", "SUCCESS")
                .timer().count()).isEqualTo(1L);

        try {
            metrics.recordOperation("bulk", true, () -> {
                throw new IllegalStateException("fail");
            });
        } catch (IllegalStateException ignored) {
        }
        assertThat(metrics.getBulkTimer().count()).isEqualTo(1L);
        assertThat(registry.get("ateng.search.operation.time")
                .tag("operation", "bulk")
                .tag("outcome", "ERROR")
                .timer().count()).isEqualTo(1L);
    }
}
