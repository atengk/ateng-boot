package io.github.atengk.boot.job.xxl.actuator;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper.XxlJobTestResult;
import io.github.atengk.boot.job.xxl.metrics.XxlJobMetricsBinder;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.support.GenericApplicationContext;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * XXL-Job Actuator 健康探针、Micrometer 度量与本地脱网测试门面单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class XxlJobActuatorAndMetricsTest {

    @Test
    @DisplayName("测试健康探针：显式禁用时报告 DISABLED")
    void testHealthIndicatorDisabled() {
        XxlJobProperties properties = new XxlJobProperties();
        properties.setEnabled(false);

        XxlJobHealthIndicator indicator = new XxlJobHealthIndicator(properties, null);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("DISABLED", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("测试健康探针：未配置调度中心地址时报告 BYPASSED 静默旁路")
    void testHealthIndicatorBypassed() {
        XxlJobProperties properties = new XxlJobProperties();
        properties.setEnabled(true);
        properties.setAdminAddresses("");

        XxlJobHealthIndicator indicator = new XxlJobHealthIndicator(properties, null);
        Health health = indicator.health();

        assertEquals(Status.UNKNOWN, health.getStatus());
        assertEquals("BYPASSED", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("测试健康探针：配置了地址但执行器未就绪时报告 NOT_INITIALIZED")
    void testHealthIndicatorNotInitialized() {
        XxlJobProperties properties = new XxlJobProperties();
        properties.setEnabled(true);
        properties.setAdminAddresses("http://127.0.0.1:8080/xxl-job-admin");

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        XxlJobHealthIndicator indicator = new XxlJobHealthIndicator(properties, beanFactory.getBeanProvider(com.xxl.job.core.executor.impl.XxlJobSpringExecutor.class));
        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("NOT_INITIALIZED", health.getDetails().get("status"));
    }

    @Test
    @DisplayName("测试 Micrometer 监控指标采集与百分位数分布")
    void testMetricsBinderRecordExecution() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        XxlJobMetricsBinder binder = new XxlJobMetricsBinder();
        binder.bindTo(registry);

        binder.recordExecution("demoJob", XxlJobMetricsBinder.STATUS_SUCCESS, "tenant-100", Duration.ofMillis(120));
        binder.recordExecution("demoJob", XxlJobMetricsBinder.STATUS_SUCCESS, "tenant-100", Duration.ofMillis(180));
        binder.recordExecution("demoJob", XxlJobMetricsBinder.STATUS_FAILURE, "tenant-100", Duration.ofMillis(50));

        Timer successTimer = registry.find(XxlJobMetricsBinder.METRIC_NAME)
                .tag(XxlJobMetricsBinder.TAG_HANDLER, "demoJob")
                .tag(XxlJobMetricsBinder.TAG_STATUS, XxlJobMetricsBinder.STATUS_SUCCESS)
                .timer();

        assertNotNull(successTimer);
        assertEquals(2, successTimer.count());

        Timer failureTimer = registry.find(XxlJobMetricsBinder.METRIC_NAME)
                .tag(XxlJobMetricsBinder.TAG_HANDLER, "demoJob")
                .tag(XxlJobMetricsBinder.TAG_STATUS, XxlJobMetricsBinder.STATUS_FAILURE)
                .timer();

        assertNotNull(failureTimer);
        assertEquals(1, failureTimer.count());
    }

    @Test
    @DisplayName("测试 XxlJobTestHelper 脱网手动执行与上下文清理")
    void testTestHelperTriggerJob() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("sampleJobBean", SampleJobBean.class);
        context.refresh();

        XxlJobTestHelper helper = new XxlJobTestHelper();
        helper.setApplicationContext(context);

        // 1. 触发正常任务
        XxlJobTestResult successResult = XxlJobTestHelper.trigger("simpleTestJob", "hello-param");
        assertTrue(successResult.isSuccess());
        assertEquals(200, successResult.handleCode());
        assertTrue(successResult.durationMs() >= 0);

        // 2. 触发失败任务
        XxlJobTestResult failResult = XxlJobTestHelper.trigger("errorTestJob", "err");
        assertFalse(failResult.isSuccess());
        assertEquals(500, failResult.handleCode());
        assertNotNull(failResult.throwable());
        assertEquals("Simulated offline error", failResult.throwable().getMessage());
    }

    @Test
    @DisplayName("测试 XxlJobEndpoint Actuator 运维端点交互")
    void testXxlJobEndpointOperations() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("sampleJobBean", SampleJobBean.class);
        context.refresh();

        XxlJobTestHelper helper = new XxlJobTestHelper();
        helper.setApplicationContext(context);

        XxlJobProperties properties = new XxlJobProperties();
        properties.setAppname("test-actuator-app");
        properties.setPort(9999);

        XxlJobEndpoint endpoint = new XxlJobEndpoint(properties);

        // GET /actuator/xxljobs
        Map<String, Object> listResult = endpoint.listJobs();
        assertEquals("test-actuator-app", listResult.get("appname"));
        assertEquals(9999, listResult.get("port"));
        assertTrue((Integer) listResult.get("jobCount") >= 2);

        // POST /actuator/xxljobs/{handler}
        Map<String, Object> triggerResponse = endpoint.triggerJob("simpleTestJob", "param123", 0, 1, null);
        assertEquals("simpleTestJob", triggerResponse.get("handler"));
        assertEquals(true, triggerResponse.get("success"));
        assertEquals(200, triggerResponse.get("handleCode"));
    }

    public static class SampleJobBean {
        @XxlJob("simpleTestJob")
        public void simpleTestJob() {
            String param = XxlJobHelper.getJobParam();
            XxlJobHelper.handleSuccess("Executed with param: " + param);
        }

        @XxlJob("errorTestJob")
        public void errorTestJob() {
            throw new RuntimeException("Simulated offline error");
        }
    }
}
