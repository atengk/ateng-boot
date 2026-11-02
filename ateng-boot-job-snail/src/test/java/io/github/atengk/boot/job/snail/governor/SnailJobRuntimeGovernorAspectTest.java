package io.github.atengk.boot.job.snail.governor;

import com.aizuda.snailjob.client.job.core.MapHandler;
import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.annotation.MapExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.client.job.core.dto.MapArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.autoconfigure.AtengSnailJobAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SnailJobRuntimeGovernor 与 SnailJobContextAspect 运行时治理联动测试
 *
 * @author Ateng
 * @since 2026-11-02
 */
@SpringBootTest(classes = {
        AtengSnailJobAutoConfiguration.class,
        SnailJobRuntimeGovernorAspectTest.TestConfig.class
})
class SnailJobRuntimeGovernorAspectTest {

    @Autowired
    private SnailJobRuntimeGovernor governor;

    @Autowired
    private TestGovernedJobService governedJobService;

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        if (governor != null) {
            governor.resumeAll();
        }
        if (governedJobService != null) {
            governedJobService.reset();
        }
    }

    @Test
    @DisplayName("测试任务处于暂停状态时切面直接跳过执行并向服务端反馈 200 成功")
    void testPausedJobSkippedByAspect() {
        JobArgs args = new JobArgs();
        args.setJobId(101L);
        args.setTaskBatchId(201L);
        args.setJobParams("{\"traceId\":\"trace-gov-01\"}");

        // 1. 未暂停时正常执行
        ExecuteResult result1 = governedJobService.executeNormal(args);
        assertThat(result1).isNotNull();
        assertThat(result1.getResult()).isEqualTo("executed-success");
        assertThat(governedJobService.getExecutionCount()).isEqualTo(1);

        // 2. 运行时动态暂停任务
        governor.pause("testGovernedJob");
        assertThat(governor.isPaused("testGovernedJob")).isTrue();

        // 3. 执行已被暂停的任务：切面拦截跳过，业务方法不执行，返回 [SKIPPED] 成功
        ExecuteResult result2 = governedJobService.executeNormal(args);
        assertThat(result2).isNotNull();
        assertThat(result2.getMessage()).contains("[SKIPPED] 任务调度已被节点级运行时治理总线暂停: testGovernedJob");
        // 核心断言：业务执行计数器未增加
        assertThat(governedJobService.getExecutionCount()).isEqualTo(1);

        // 4. 恢复任务执行
        governor.resume("testGovernedJob");
        assertThat(governor.isPaused("testGovernedJob")).isFalse();

        // 5. 再次执行成功调用业务逻辑
        ExecuteResult result3 = governedJobService.executeNormal(args);
        assertThat(result3).isNotNull();
        assertThat(result3.getResult()).isEqualTo("executed-success");
        assertThat(governedJobService.getExecutionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("测试切片任务自动感知根任务暂停状态并安全跳过")
    void testSliceJobInheritsRootJobPause() {
        MapArgs mapArgs = new MapArgs();
        mapArgs.setJobId(102L);
        mapArgs.setTaskBatchId(202L);
        mapArgs.setTaskName("slice1");
        mapArgs.setJobParams("{\"traceId\":\"trace-gov-slice\"}");

        MapHandler<Object> mockHandler = (list, taskName) -> ExecuteResult.success();

        // 1. 暂停根任务
        governor.pause("mapReduceJob");

        // 2. 触发切片任务，切面通过复合命名检测到根任务已暂停，安全跳过
        ExecuteResult result = governedJobService.executeMap(mapArgs, mockHandler);
        assertThat(result).isNotNull();
        assertThat(result.getMessage()).contains("[SKIPPED]");
        assertThat(governedJobService.getSliceExecutionCount()).isEqualTo(0);

        // 3. 恢复根任务
        governor.resume("mapReduceJob");
        ExecuteResult resultAfterResume = governedJobService.executeMap(mapArgs, mockHandler);
        assertThat(resultAfterResume).isNotNull();
        assertThat(resultAfterResume.getResult()).isEqualTo("slice-executed");
        assertThat(governedJobService.getSliceExecutionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("测试返回基本类型 (boolean/int) 的任务在暂停跳过时安全返回默认值，防拆箱 NPE")
    void testPrimitiveReturnTypeSkippedWithoutNpe() {
        governor.pause("testPrimitiveJob");
        boolean boolResult = governedJobService.executeBooleanJob();
        assertThat(boolResult).isTrue();

        governor.pause("testIntJob");
        int intResult = governedJobService.executeIntJob();
        assertThat(intResult).isEqualTo(0);
    }

    @TestConfiguration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class TestConfig {

        @Bean
        public TestGovernedJobService testGovernedJobService() {
            return new TestGovernedJobService();
        }
    }

    @Service
    public static class TestGovernedJobService {

        final AtomicInteger executionCount = new AtomicInteger(0);
        final AtomicInteger sliceExecutionCount = new AtomicInteger(0);

        public int getExecutionCount() {
            return executionCount.get();
        }

        public int getSliceExecutionCount() {
            return sliceExecutionCount.get();
        }

        public void reset() {
            executionCount.set(0);
            sliceExecutionCount.set(0);
        }

        @JobExecutor(name = "testGovernedJob")
        @AtengSnailJob
        public ExecuteResult executeNormal(JobArgs args) {
            executionCount.incrementAndGet();
            return ExecuteResult.success("executed-success");
        }

        @MapExecutor(taskName = "slice1")
        @AtengSnailJob(value = "mapReduceJob")
        public ExecuteResult executeMap(MapArgs args, MapHandler handler) {
            sliceExecutionCount.incrementAndGet();
            return ExecuteResult.success("slice-executed");
        }

        @JobExecutor(name = "testPrimitiveJob")
        @AtengSnailJob
        public boolean executeBooleanJob() {
            return false;
        }

        @JobExecutor(name = "testIntJob")
        @AtengSnailJob
        public int executeIntJob() {
            return 42;
        }
    }
}
