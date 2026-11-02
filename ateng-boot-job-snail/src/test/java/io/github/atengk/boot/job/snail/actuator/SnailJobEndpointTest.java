package io.github.atengk.boot.job.snail.actuator;

import com.aizuda.snailjob.client.core.cache.RetryerInfoCache;
import com.aizuda.snailjob.client.core.retryer.RetryType;
import com.aizuda.snailjob.client.core.retryer.RetryerInfo;
import com.aizuda.snailjob.client.job.core.cache.JobExecutorInfoCache;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.client.job.core.dto.JobExecutorInfo;
import io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine;
import io.github.atengk.boot.job.snail.governor.SnailJobRuntimeGovernor;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SnailJobEndpoint 运维管控端点单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailJobEndpointTest {

    private SnailJobEndpoint endpoint;
    private SnailJobProperties properties;
    private GracefulDrainStateMachine drainStateMachine;
    private SnailJobRuntimeGovernor governor;

    public static class SampleJobService {
        public String executeJob(JobArgs args) {
            return "executed:" + (args != null ? args.getJobParams() : "null");
        }

        public String executeNoArg() {
            return "noArgExecuted";
        }

        public void sampleRetryMethod(String param) {
        }
    }

    private final SampleJobService sampleService = new SampleJobService();

    @BeforeEach
    public void setUp() throws Exception {
        properties = new SnailJobProperties();
        properties.setGroup("test-group");
        properties.setNamespace("test-namespace");
        properties.setServer("127.0.0.1:17888");
        properties.setPort(17889);

        drainStateMachine = new GracefulDrainStateMachine(properties);
        governor = new SnailJobRuntimeGovernor();
        endpoint = new SnailJobEndpoint(properties, drainStateMachine, governor);

        // 注册测试调度任务
        Method jobMethod = SampleJobService.class.getMethod("executeJob", JobArgs.class);
        JobExecutorInfo jobInfo = new JobExecutorInfo("testSampleJob", jobMethod, Collections.emptyMap(), null, null, sampleService);
        JobExecutorInfoCache.put(jobInfo);

        // 注册测试重试场景
        Method retryMethod = SampleJobService.class.getMethod("sampleRetryMethod", String.class);
        RetryerInfo retryerInfo = new RetryerInfo(
                "testSampleScene",
                SampleJobService.class.getName(),
                Collections.emptySet(),
                Collections.emptySet(),
                sampleService,
                retryMethod,
                RetryType.ONLY_LOCAL,
                3,
                5,
                null,
                "biz123",
                null,
                false,
                null,
                false,
                false,
                1000,
                null,
                null
        );
        RetryerInfoCache.put(retryerInfo);
    }

    @AfterEach
    public void tearDown() {
        try {
            Field field = JobExecutorInfoCache.class.getDeclaredField("JOB_EXECUTOR_REPOSITORY");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<String, JobExecutorInfo> map = (ConcurrentHashMap<String, JobExecutorInfo>) field.get(null);
            if (map != null) {
                map.remove("testSampleJob");
            }
        } catch (Throwable ignored) {
        }
        try {
            Field retryField = RetryerInfoCache.class.getDeclaredField("RETRY_HANDLER_REPOSITORY");
            retryField.setAccessible(true);
            Object tableObj = retryField.get(null);
            if (tableObj != null) {
                Method removeMethod = tableObj.getClass().getMethod("remove", Object.class, Object.class);
                removeMethod.setAccessible(true);
                removeMethod.invoke(tableObj, "testSampleScene", SampleJobService.class.getName());
            }
        } catch (Throwable ignored) {
        }
    }

    @Test
    public void testListJobs() {
        Map<String, Object> listResult = endpoint.listJobs();
        assertNotNull(listResult);
        assertEquals("test-group", listResult.get("group"));
        assertEquals("test-namespace", listResult.get("namespace"));
        assertEquals("127.0.0.1:17888", listResult.get("server"));
        assertEquals(17889, listResult.get("port"));
        assertEquals(true, listResult.get("enabled"));
        assertEquals(false, listResult.get("draining"));
        assertEquals(0, listResult.get("inFlightJobs"));
        assertEquals(0, listResult.get("inFlightRetries"));

        assertTrue(((Number) listResult.get("jobCount")).intValue() >= 1);
        assertTrue(((Number) listResult.get("retryCount")).intValue() >= 1);
    }

    @Test
    public void testJobDetailForJob() {
        Map<String, Object> detail = endpoint.jobDetail("testSampleJob");
        assertNotNull(detail);
        assertEquals(true, detail.get("exists"));
        assertEquals("JOB", detail.get("type"));
        assertEquals("testSampleJob", detail.get("executorName"));
        assertEquals(SampleJobService.class.getName(), detail.get("targetClass"));
        assertEquals("executeJob", detail.get("methodName"));
    }

    @Test
    public void testJobDetailForRetryScene() {
        Map<String, Object> detail = endpoint.jobDetail("testSampleScene");
        assertNotNull(detail);
        assertEquals(true, detail.get("exists"));
        assertEquals("RETRY", detail.get("type"));
        assertEquals("testSampleScene", detail.get("scene"));
        assertEquals(SampleJobService.class.getName(), detail.get("executorClassName"));
        assertEquals(3, detail.get("localTimes"));
    }

    @Test
    public void testJobDetailNotFound() {
        Map<String, Object> detail = endpoint.jobDetail("unknownJobName");
        assertNotNull(detail);
        assertEquals(false, detail.get("exists"));
    }

    @Test
    public void testTriggerJobSuccess() {
        Map<String, Object> triggerResult = endpoint.triggerJob("testSampleJob", "{\"message\":\"hello\"}");
        assertNotNull(triggerResult);
        assertEquals(true, triggerResult.get("success"));
        assertEquals("testSampleJob", triggerResult.get("jobName"));
        assertEquals("{\"message\":\"hello\"}", triggerResult.get("args"));
        assertEquals("executed:{\"message\":\"hello\"}", triggerResult.get("result"));
        assertNotNull(triggerResult.get("traceId"));
        assertTrue((Long) triggerResult.get("durationMs") >= 0);
    }

    @Test
    public void testTriggerJobNotFound() {
        Map<String, Object> triggerResult = endpoint.triggerJob("nonExistentJob", "args");
        assertNotNull(triggerResult);
        assertEquals(false, triggerResult.get("success"));
        assertTrue(((String) triggerResult.get("message")).contains("未找到"));
    }

    @Test
    public void testListJobsAndDetailWithGovernor() {
        governor.pause("testSampleJob");

        Map<String, Object> listResult = endpoint.listJobs();
        assertNotNull(listResult);
        assertEquals(1, listResult.get("pausedCount"));
        assertTrue(((java.util.List<?>) listResult.get("pausedJobs")).contains("testSampleJob"));

        Map<String, Object> detail = endpoint.jobDetail("testSampleJob");
        assertEquals(true, detail.get("isPaused"));

        Map<String, Object> unpausedDetail = endpoint.jobDetail("otherJob");
        assertEquals(false, unpausedDetail.get("isPaused"));
    }

    @Test
    public void testManageJobPauseAndResume() {
        // 1. 通过 manageJob 暂停
        Map<String, Object> pauseResult = endpoint.manageJob("testSampleJob", "pause", null);
        assertNotNull(pauseResult);
        assertEquals(true, pauseResult.get("success"));
        assertEquals("pause", pauseResult.get("action"));
        assertEquals(true, pauseResult.get("paused"));
        assertEquals(true, pauseResult.get("statusChanged"));
        assertTrue(governor.isPaused("testSampleJob"));

        // 2. 通过 manageJob 恢复
        Map<String, Object> resumeResult = endpoint.manageJob("testSampleJob", "resume", null);
        assertNotNull(resumeResult);
        assertEquals(true, resumeResult.get("success"));
        assertEquals("resume", resumeResult.get("action"));
        assertEquals(false, resumeResult.get("paused"));
        assertEquals(true, resumeResult.get("statusChanged"));
        assertFalse(governor.isPaused("testSampleJob"));

        // 3. 缺省 action 触发 triggerJob
        Map<String, Object> triggerResult = endpoint.manageJob("testSampleJob", null, "{\"message\":\"via-manage\"}");
        assertNotNull(triggerResult);
        assertEquals(true, triggerResult.get("success"));
        assertEquals("executed:{\"message\":\"via-manage\"}", triggerResult.get("result"));
    }

    @Test
    public void testManageGovernorResumeAll() {
        governor.pause("jobA");
        governor.pause("jobB");
        assertEquals(2, governor.getPausedCount());

        Map<String, Object> result = endpoint.manageGovernor("resumeAll", null);
        assertNotNull(result);
        assertEquals(true, result.get("success"));
        assertEquals(2, result.get("resumedCount"));
        assertEquals(0, governor.getPausedCount());
    }
}
