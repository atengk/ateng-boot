package io.github.atengk.boot.web.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全链路追踪工具类单测
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("全链路追踪工具类单测")
class TraceUtilTest {

    @AfterEach
    void tearDown() {
        TraceUtil.clear();
    }

    @Test
    @DisplayName("验证 setTraceId 与 getTraceId 的生命周期与清理")
    void shouldSetAndGetTraceId() {
        assertThat(TraceUtil.getTraceId()).isNull();

        TraceUtil.setTraceId("test-trace-id-12345");
        assertThat(TraceUtil.getTraceId()).isEqualTo("test-trace-id-12345");
        assertThat(MDC.get(TraceFilter.MDC_TRACE_ID_KEY)).isEqualTo("test-trace-id-12345");

        TraceUtil.clear();
        assertThat(TraceUtil.getTraceId()).isNull();
        assertThat(MDC.get(TraceFilter.MDC_TRACE_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("setTraceId 传入空串或 null 时应安全清理 MDC")
    void shouldClearWhenSetNullOrBlank() {
        TraceUtil.setTraceId("test-trace-id-67890");
        assertThat(TraceUtil.getTraceId()).isEqualTo("test-trace-id-67890");

        TraceUtil.setTraceId(null);
        assertThat(TraceUtil.getTraceId()).isNull();

        TraceUtil.setTraceId("abc");
        TraceUtil.setTraceId("");
        assertThat(TraceUtil.getTraceId()).isNull();
    }
}
