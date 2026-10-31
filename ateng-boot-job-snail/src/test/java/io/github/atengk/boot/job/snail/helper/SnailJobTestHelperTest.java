package io.github.atengk.boot.job.snail.helper;

import com.aizuda.snailjob.client.core.annotation.Retryable;
import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.client.job.core.dto.ShardingJobArgs;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.annotation.AtengSnailRetry;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper.SnailJobTestResult;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper.SnailRetryTestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.support.GenericApplicationContext;

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
}
