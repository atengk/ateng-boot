package io.github.atengk.boot.job.xxl.actuator;

import io.github.atengk.boot.job.xxl.governor.JobRuntimeGovernor;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XXL-Job Actuator 运维与运行时治理端点单测
 *
 * @author Ateng
 * @since 2026-11-01
 */
class XxlJobEndpointTest {

    private JobRuntimeGovernor governor;
    private XxlJobProperties properties;
    private XxlJobEndpoint endpoint;

    @BeforeEach
    void setUp() {
        governor = new JobRuntimeGovernor();
        properties = new XxlJobProperties();
        properties.setAppname("demo-executor");
        properties.setPort(9999);
        properties.setEnabled(true);
        properties.setAdminAddresses("http://127.0.0.1:8080/xxl-job-admin");
        endpoint = new XxlJobEndpoint(properties, governor);
    }

    @AfterEach
    void tearDown() {
        governor.clear();
    }

    @Test
    @DisplayName("测试 listJobs：包含基本配置、任务总数及节点静默状态")
    void testListJobs() {
        governor.pause("reportJob");

        Map<String, Object> result = endpoint.listJobs();
        assertThat(result.get("appname")).isEqualTo("demo-executor");
        assertThat(result.get("port")).isEqualTo(9999);
        assertThat(result.get("enabled")).isEqualTo(true);
        assertThat(result.get("pausedCount")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        Set<String> paused = (Set<String>) result.get("pausedHandlers");
        assertThat(paused).containsExactly("reportJob");
    }

    @Test
    @DisplayName("测试 jobDetail：查询任务详情与静默标记")
    void testJobDetail() {
        governor.pause("testHandler");

        Map<String, Object> detail = endpoint.jobDetail("testHandler");
        assertThat(detail.get("handler")).isEqualTo("testHandler");
        assertThat(detail.get("paused")).isEqualTo(true);
    }

    @Test
    @DisplayName("测试 operateJob：支持 pause 与 resume 操作")
    void testOperateJobPauseAndResume() {
        // 1. 暂停操作
        Map<String, Object> pauseResult = endpoint.operateJob("demoJob", "pause");
        assertThat(pauseResult.get("success")).isEqualTo(true);
        assertThat(pauseResult.get("paused")).isEqualTo(true);
        assertThat(pauseResult.get("action")).isEqualTo("pause");
        assertThat(governor.isPaused("demoJob")).isTrue();

        // 2. 恢复操作
        Map<String, Object> resumeResult = endpoint.operateJob("demoJob", "resume");
        assertThat(resumeResult.get("success")).isEqualTo(true);
        assertThat(resumeResult.get("paused")).isEqualTo(false);
        assertThat(resumeResult.get("action")).isEqualTo("resume");
        assertThat(governor.isPaused("demoJob")).isFalse();

        // 3. 非法动作防卫
        Map<String, Object> invalidResult = endpoint.operateJob("demoJob", "invalid_action");
        assertThat(invalidResult.get("success")).isEqualTo(false);
        assertThat(invalidResult.get("message")).toString().contains("不支持的治理动作");
    }

    @Test
    @DisplayName("测试 operateJob 支持精确动作映射")
    void testOperateJobActionMapping() {
        Map<String, Object> pauseResult = endpoint.operateJob("actionJob", "pause");
        assertThat(pauseResult.get("success")).isEqualTo(true);
        assertThat(pauseResult.get("paused")).isEqualTo(true);
        assertThat(governor.isPaused("actionJob")).isTrue();

        Map<String, Object> resumeResult = endpoint.operateJob("actionJob", "resume");
        assertThat(resumeResult.get("success")).isEqualTo(true);
        assertThat(resumeResult.get("paused")).isEqualTo(false);
        assertThat(governor.isPaused("actionJob")).isFalse();
    }

    @Test
    @DisplayName("测试 governor 为空时的平滑降级表现")
    void testNullGovernorDegradation() {
        XxlJobEndpoint rawEndpoint = new XxlJobEndpoint(properties, null);
        Map<String, Object> listResult = rawEndpoint.listJobs();
        assertThat(listResult.get("pausedCount")).isEqualTo(0);

        Map<String, Object> operateResult = rawEndpoint.operateJob("anyJob", "pause");
        assertThat(operateResult.get("success")).isEqualTo(false);
        assertThat(operateResult.get("message")).toString().contains("JobRuntimeGovernor 未装配");
    }
}
