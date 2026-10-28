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
}
