package io.github.atengk.boot.job.snail.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SnailJobMetricsBinder 监控度量单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailJobMetricsBinderTest {

    private MeterRegistry registry;
    private SnailJobMetricsBinder binder;

    @BeforeEach
    public void setUp() {
        registry = new SimpleMeterRegistry();
        binder = new SnailJobMetricsBinder();
        binder.bindTo(registry);
    }

    @Test
    public void testRecordJobExecutionSuccess() {
        binder.recordJobExecution("sampleJob", SnailJobMetricsBinder.STATUS_SUCCESS, "tenantA", Duration.ofMillis(120));

        Timer timer = registry.find(SnailJobMetricsBinder.JOB_METRIC_NAME)
                .tag(SnailJobMetricsBinder.TAG_JOB, "sampleJob")
                .tag(SnailJobMetricsBinder.TAG_STATUS, SnailJobMetricsBinder.STATUS_SUCCESS)
                .tag(SnailJobMetricsBinder.TAG_TENANT, "tenantA")
                .timer();

        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 120);
    }

    @Test
    public void testRecordJobExecutionFailure() {
        binder.recordJobExecution("sampleJob", SnailJobMetricsBinder.STATUS_FAILURE, null, Duration.ofMillis(250));

        Timer timer = registry.find(SnailJobMetricsBinder.JOB_METRIC_NAME)
                .tag(SnailJobMetricsBinder.TAG_JOB, "sampleJob")
                .tag(SnailJobMetricsBinder.TAG_STATUS, SnailJobMetricsBinder.STATUS_FAILURE)
                .tag(SnailJobMetricsBinder.TAG_TENANT, "default")
                .timer();

        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    public void testRecordRetryExecutionSuccess() {
        binder.recordRetryExecution("sampleRetryScene", SnailJobMetricsBinder.STATUS_SUCCESS, "tenantB", Duration.ofMillis(45));

        Timer timer = registry.find(SnailJobMetricsBinder.RETRY_METRIC_NAME)
                .tag(SnailJobMetricsBinder.TAG_SCENE, "sampleRetryScene")
                .tag(SnailJobMetricsBinder.TAG_STATUS, SnailJobMetricsBinder.STATUS_SUCCESS)
                .tag(SnailJobMetricsBinder.TAG_TENANT, "tenantB")
                .timer();

        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    public void testRecordRetryExecutionFailure() {
        binder.recordRetryExecution("sampleRetryScene", SnailJobMetricsBinder.STATUS_FAILURE, "tenantB", Duration.ofMillis(80));

        Timer timer = registry.find(SnailJobMetricsBinder.RETRY_METRIC_NAME)
                .tag(SnailJobMetricsBinder.TAG_SCENE, "sampleRetryScene")
                .tag(SnailJobMetricsBinder.TAG_STATUS, SnailJobMetricsBinder.STATUS_FAILURE)
                .tag(SnailJobMetricsBinder.TAG_TENANT, "tenantB")
                .timer();

        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    public void testNullSafety() {
        SnailJobMetricsBinder unbound = new SnailJobMetricsBinder();
        // Should not throw NPE when registry is null
        assertDoesNotThrow(() -> unbound.recordJobExecution("job", "SUCCESS", "tenant", Duration.ofMillis(10)));
        assertDoesNotThrow(() -> unbound.recordRetryExecution("scene", "SUCCESS", "tenant", Duration.ofMillis(10)));

        // Should not throw NPE when duration is null
        assertDoesNotThrow(() -> binder.recordJobExecution("job", "SUCCESS", "tenant", null));
        assertDoesNotThrow(() -> binder.recordRetryExecution("scene", "SUCCESS", "tenant", null));
    }
}
