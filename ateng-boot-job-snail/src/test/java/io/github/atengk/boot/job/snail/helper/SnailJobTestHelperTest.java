package io.github.atengk.boot.job.snail.helper;

import com.aizuda.snailjob.client.core.annotation.Retryable;
import com.aizuda.snailjob.client.job.core.MapHandler;
import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.annotation.MapExecutor;
import com.aizuda.snailjob.client.job.core.annotation.MergeReduceExecutor;
import com.aizuda.snailjob.client.job.core.annotation.ReduceExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.client.job.core.dto.MapArgs;
import com.aizuda.snailjob.client.job.core.dto.MergeReduceArgs;
import com.aizuda.snailjob.client.job.core.dto.ReduceArgs;
import com.aizuda.snailjob.client.job.core.dto.ShardingJobArgs;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.annotation.AtengSnailRetry;
import io.github.atengk.boot.job.snail.context.SnailJobContext;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import io.github.atengk.boot.job.snail.governor.SnailJobRuntimeGovernor;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper.SnailJobTestResult;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper.SnailRetryTestResult;
import io.github.atengk.boot.job.snail.model.ShardingContext;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.util.unit.DataSize;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SnailJobTestHelper 单元测试与脱网沙箱校验
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailJobTestHelperTest {

    public static class SampleTestService {

        @JobExecutor(name = "simpleJob")
        public String executeSimple() {
            return "simpleDone";
        }

        @JobExecutor(name = "jobWithArgs")
        public String executeWithArgs(JobArgs args) {
            String tenant = TenantContextHolder.getTenantId();
            String user = UserContextHolder.getExplicit();
            String traceId = MDC.get("traceId");
            return "argsDone:" + args.getJobParams() + "|tenant:" + tenant + "|user:" + user + "|hasTrace:" + (traceId != null);
        }

        @AtengSnailJob("shardingJob")
        public String executeSharding(ShardingJobArgs args) {
            return "shard:" + args.getShardingIndex() + "/" + args.getShardingTotal();
        }

        @JobExecutor(name = "failingJob")
        public void executeFailing() {
            throw new IllegalStateException("Test job failure");
        }

        @Retryable(scene = "sampleRetryScene")
        public String executeRetry(String message) {
            return "retryProcessed:" + message;
        }

        @AtengSnailRetry(scene = "failingRetryScene")
        public void executeFailingRetry() {
            throw new RuntimeException("Test retry failure");
        }

        @MapExecutor(taskName = "sliceA")
        public String executeSliceA(MapArgs args, MapHandler handler) {
            return "sliceADone:" + args.getTaskName() + "|" + (handler != null);
        }

        @ReduceExecutor
        public String executeReduce(ReduceArgs args) {
            return "reduceDone:" + args.getJobParams();
        }

        @MergeReduceExecutor
        public String executeMergeReduce(MergeReduceArgs args) {
            return "mergeReduceDone:" + args.getJobParams();
        }

        @AtengSnailJob("multiParamValidatedJob")
        public String executeMultiParamValidated(
                ShardingContext sharding,
                SnailJobContext jobContext,
                @Valid HelperDto dto) {
            return dto.getOrderNo() + "|count=" + dto.getCount()
                    + "|shard=" + sharding.getShardIndex() + "/" + sharding.getShardTotal()
                    + "|hasCtx=" + (jobContext != null);
        }
    }

    public static class HelperDto {
        @NotBlank(message = "orderNo不能为空")
        private String orderNo;
        @Min(value = 1, message = "count必须大于0")
        private int count;

        public String getOrderNo() {
            return orderNo;
        }

        public void setOrderNo(String orderNo) {
            this.orderNo = orderNo;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    private GenericApplicationContext context;
    private SnailJobTestHelper helper;
    private SampleTestService service;

    @BeforeEach
    public void setUp() {
        service = new SampleTestService();
        context = new GenericApplicationContext();
        context.registerBean("sampleTestService", SampleTestService.class, () -> service);
        context.refresh();

        helper = new SnailJobTestHelper();
        helper.setApplicationContext(context);
    }

    @AfterEach
    public void tearDown() {
        SnailJobTestHelper.clear();
        if (context != null) {
            context.close();
        }
    }

    @Test
    public void testTriggerSimpleJob() {
        SnailJobTestResult result = SnailJobTestHelper.triggerJob("simpleJob");
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("simpleDone", result.result());
        assertNotNull(result.traceId());
        assertTrue(result.durationMs() >= 0);
        assertNull(result.throwable());
    }

    @Test
    public void testTriggerJobWithArgsAndContexts() {
        SnailJobTestResult result = SnailJobTestHelper.triggerJob("jobWithArgs", "helloParam", 0, 1, "TENANT-007");
        assertNotNull(result);
        assertTrue(result.isSuccess());
        String msg = (String) result.result();
        assertTrue(msg.contains("argsDone:helloParam"));
        assertTrue(msg.contains("tenant:TENANT-007"));
        assertTrue(msg.contains("user:SYSTEM_TEST"));
        assertTrue(msg.contains("hasTrace:true"));

        // 验证现场清理无泄露
        assertNull(TenantContextHolder.getTenantId());
        assertNull(UserContextHolder.getExplicit());
        assertNull(MDC.get("traceId"));
        assertNull(SnailJobContextHolder.get());
    }

    @Test
    public void testTriggerShardingJob() {
        SnailJobTestResult result = SnailJobTestHelper.triggerJob("shardingJob", null, 2, 5);
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("shard:2/5", result.result());
    }

    @Test
    public void testTriggerFailingJob() {
        SnailJobTestResult result = SnailJobTestHelper.triggerJob("failingJob");
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertNotNull(result.throwable());
        assertTrue(result.throwable().getMessage().contains("Test job failure"));
    }

    @Test
    public void testTriggerNotFoundJobThrows() {
        assertThrows(BusinessException.class, () -> SnailJobTestHelper.triggerJob("nonExistentJob"));
    }

    @Test
    public void testTriggerRetrySuccess() {
        SnailRetryTestResult result = SnailJobTestHelper.triggerRetry("sampleRetryScene", "orderData");
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("retryProcessed:orderData", result.result());
        assertNotNull(result.traceId());
        assertNull(result.throwable());
    }

    @Test
    public void testTriggerRetryFailure() {
        SnailRetryTestResult result = SnailJobTestHelper.triggerRetry("failingRetryScene");
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertNotNull(result.throwable());
        assertTrue(result.throwable().getMessage().contains("Test retry failure"));
    }

    @Test
    public void testTriggerNotFoundRetryThrows() {
        assertThrows(BusinessException.class, () -> SnailJobTestHelper.triggerRetry("nonExistentScene"));
    }

    @Test
    public void testTriggerMapReduceViaHelper() {
        SnailJobTestResult mapResult = SnailJobTestHelper.triggerMap("SampleTestService", "sliceA", "p1");
        assertNotNull(mapResult);
        assertTrue(mapResult.isSuccess());
        assertEquals("sliceADone:sliceA|true", mapResult.result());

        SnailJobTestResult reduceResult = SnailJobTestHelper.triggerReduce("SampleTestService", "p2");
        assertNotNull(reduceResult);
        assertTrue(reduceResult.isSuccess());
        assertEquals("reduceDone:p2", reduceResult.result());

        SnailJobTestResult mergeResult = SnailJobTestHelper.triggerMergeReduce("SampleTestService", "p3");
        assertNotNull(mergeResult);
        assertTrue(mergeResult.isSuccess());
        assertEquals("mergeReduceDone:p3", mergeResult.result());
    }

    @Test
    public void testContextSwitchAndSymmetricClearIsolation() {
        assertFalse(helper.getRegisteredJobNames().isEmpty());
        assertFalse(helper.getRegisteredRetryScenes().isEmpty());

        // 关闭旧容器并切换到全新隔离容器，仅注册 simpleJob
        context.close();
        GenericApplicationContext switchedContext = new GenericApplicationContext();
        try {
            switchedContext.registerBean("switchedBean", SimpleOnlyService.class, SimpleOnlyService::new);
            switchedContext.refresh();
            helper.setApplicationContext(switchedContext);

            assertTrue(helper.getRegisteredJobNames().contains("switchedOnlyJob"));
            assertFalse(helper.getRegisteredRetryScenes().contains("sampleRetryScene"));
            assertThrows(BusinessException.class, () -> SnailJobTestHelper.triggerJob("shardingJob"));

            SnailJobTestResult switchedResult = SnailJobTestHelper.triggerJob("switchedOnlyJob");
            assertTrue(switchedResult.isSuccess());
            assertEquals("switchedDone", switchedResult.result());
        } finally {
            SnailJobTestHelper.clear();
            switchedContext.close();
            // 关闭容器后不应残存已销毁容器的任务或重试场景
            assertTrue(helper.getRegisteredJobNames().isEmpty());
            assertTrue(helper.getRegisteredRetryScenes().isEmpty());
        }
    }

    public static class SimpleOnlyService {

        @JobExecutor(name = "switchedOnlyJob")
        public String executeSwitched() {
            return "switchedDone";
        }
    }

    @Test
    public void testMultiParamBindingValidationMalformedJsonPayloadAndGovernor() {
        GenericApplicationContext governedCtx = new GenericApplicationContext();
        SnailJobRuntimeGovernor governor = new SnailJobRuntimeGovernor();
        SnailJobProperties props = new SnailJobProperties();
        props.setMaxPayloadSize(DataSize.ofBytes(80));
        governedCtx.registerBean(SnailJobRuntimeGovernor.class, () -> governor);
        governedCtx.registerBean(SnailJobProperties.class, () -> props);
        governedCtx.registerBean("sampleTestService", SampleTestService.class, SampleTestService::new);
        governedCtx.refresh();

        try {
            helper.setApplicationContext(governedCtx);

            // 1. 多参数绑定（ShardingContext + SnailJobContext + @Valid DTO）
            SnailJobTestResult okResult = SnailJobTestHelper.triggerJob(
                    "multiParamValidatedJob", "{\"orderNo\":\"ORD-100\",\"count\":3}", 1, 4);
            assertTrue(okResult.isSuccess());
            assertEquals("ORD-100|count=3|shard=1/4|hasCtx=true", okResult.result());

            // 2. 畸形 JSON 显式抛出语义异常，严禁透传字符串引发 argument type mismatch
            SnailJobTestResult malformedResult = SnailJobTestHelper.triggerJob("multiParamValidatedJob", "{broken-json");
            assertFalse(malformedResult.isSuccess());
            assertNotNull(malformedResult.throwable());
            assertTrue(malformedResult.throwable() instanceof BusinessException);
            assertTrue(malformedResult.throwable().getMessage().contains("JSON"));

            // 3. @Valid JSR-303 参数校验 Fail-Fast
            SnailJobTestResult invalidResult = SnailJobTestHelper.triggerJob(
                    "multiParamValidatedJob", "{\"orderNo\":\"\",\"count\":0}");
            assertFalse(invalidResult.isSuccess());
            assertTrue(invalidResult.throwable() instanceof BusinessException);
            assertTrue(invalidResult.throwable().getMessage().contains("JSR-303"));

            // 4. maxPayloadSize 载荷超限拦截
            String oversized = "{\"orderNo\":\"" + "A".repeat(90) + "\",\"count\":1}";
            SnailJobTestResult payloadResult = SnailJobTestHelper.triggerJob("multiParamValidatedJob", oversized);
            assertFalse(payloadResult.isSuccess());
            assertTrue(payloadResult.throwable() instanceof BusinessException);
            assertTrue(payloadResult.throwable().getMessage().contains("maxPayloadSize"));

            // 5. SnailJobRuntimeGovernor 暂停态拦截
            governor.pause("multiParamValidatedJob");
            SnailJobTestResult pausedResult = SnailJobTestHelper.triggerJob(
                    "multiParamValidatedJob", "{\"orderNo\":\"ORD-200\",\"count\":1}");
            assertFalse(pausedResult.isSuccess());
            assertTrue(pausedResult.throwable() instanceof BusinessException);
            assertTrue(pausedResult.throwable().getMessage().contains("暂停"));
        } finally {
            SnailJobTestHelper.clear();
            governedCtx.close();
        }
    }
}
