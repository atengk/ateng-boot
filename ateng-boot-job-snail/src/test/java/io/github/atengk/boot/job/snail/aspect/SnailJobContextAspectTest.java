package io.github.atengk.boot.job.snail.aspect;

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
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.autoconfigure.AtengSnailJobAutoConfiguration;
import io.github.atengk.boot.job.snail.context.SnailJobContext;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import io.github.atengk.boot.job.snail.enums.JobTenantMode;
import io.github.atengk.boot.job.snail.event.SnailJobAlertEvent;
import io.github.atengk.boot.job.snail.event.SnailJobTimeoutEvent;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper.SnailJobTestResult;
import io.github.atengk.boot.job.snail.interceptor.SnailJobExecutionContext;
import io.github.atengk.boot.job.snail.interceptor.SnailJobInterceptor;
import io.github.atengk.boot.job.snail.model.ShardingContext;
import io.github.atengk.boot.job.snail.watchdog.JobTimeoutFallbackHandler;
import io.github.atengk.boot.job.snail.watchdog.SnailJobExecutionMetadata;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SnailJob 上下文切面、JSR-303 参数反序列化防卫与硬看门狗测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest(classes = {
        AtengSnailJobAutoConfiguration.class,
        SnailJobContextAspectTest.TestJobConfig.class
})
class SnailJobContextAspectTest {

    @Autowired
    private MockJobService mockJobService;

    @Autowired
    private MockMapReduceJobService mockMapReduceJobService;

    @Autowired
    private StandaloneSliceJobService standaloneSliceJobService;

    @Autowired
    private TestTimeoutEventListener timeoutEventListener;

    @Autowired
    private StandaloneGovernanceJobService governanceJobService;

    @Autowired
    private CapturingMetadataInterceptor metadataInterceptor;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
        timeoutEventListener.clear();
        governanceJobService.reset();
        metadataInterceptor.clear();
        SnailJobTestHelper.clear();
    }

    @Test
    @DisplayName("测试原生 @JobExecutor 方法自动注入 MDC traceId、User 与上下文现场恢复")
    void testNativeJobTraceAndUserContextInjection() {
        MDC.put(SnailJobContextAspect.TRACE_ID_KEY, "prev-trace-123");
        UserContextHolder.set("prev-user");

        JobArgs args = new JobArgs();
        args.setJobId(1001L);
        args.setTaskBatchId(2001L);
        args.setExecutorInfo("testNativeJob");
        args.setJobParams("{\"traceId\":\"custom-trace-999\",\"userId\":\"admin\"}");

        ExecuteResult result = mockJobService.executeNative(args);
        assertThat(result).isNotNull();
        assertThat(result.getMessage()).isEqualTo("Task executed successfully");

        // 验证执行完成后现场无缝恢复，无任何线程污染
        assertThat(MDC.get(SnailJobContextAspect.TRACE_ID_KEY)).isEqualTo("prev-trace-123");
        assertThat(UserContextHolder.getExplicit()).isEqualTo("prev-user");
    }

    @Test
    @DisplayName("测试多租户策略：JobTenantMode.IGNORE 自动忽略租户过滤")
    void testTenantModeIgnore() {
        TenantContextHolder.setTenantId("outer-tenant");

        JobArgs args = new JobArgs();
        args.setJobParams("{\"tenantId\":\"ignored-tenant\"}");

        mockJobService.executeTenantIgnore(args);

        // 终态现场恢复
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("outer-tenant");
    }

    @Test
    @DisplayName("测试多租户策略：JobTenantMode.PARAM 从任务参数中智能提取租户")
    void testTenantModeParam() {
        JobArgs args = new JobArgs();
        args.setJobParams("{\"tenantId\":\"tenant-888\"}");

        mockJobService.executeTenantParam(args);

        // 终态现场清理
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("测试强类型 DTO 自动反序列化与 JSR-303 Fail-Fast 校验（合法入参）")
    void testDtoDeserializationAndValidParameter() {
        JobArgs args = new JobArgs();
        args.setJobParams("{\"name\":\"test-order\",\"count\":5,\"tenantId\":\"tenant-dto-1\"}");

        ExecuteResult result = mockJobService.executeWithDto(args, null);
        assertThat(result).isNotNull();
        assertThat(result.getResult()).isEqualTo("order: test-order, count: 5");
    }

    @Test
    @DisplayName("测试强类型 DTO JSR-303 格式校验违规时触发快速失败 Fail-Fast")
    void testDtoValidationFailFastWhenConstraintViolated() {
        JobArgs args = new JobArgs();
        // count 为 0，不满足 @Min(1)
        args.setJobParams("{\"name\":\"test-order\",\"count\":0}");

        assertThatThrownBy(() -> mockJobService.executeWithDto(args, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("参数校验失败");
    }

    @Test
    @DisplayName("测试强类型 DTO 必填字段缺失时触发 Fail-Fast")
    void testDtoValidationFailFastWhenBlank() {
        JobArgs args = new JobArgs();
        // name 缺失，不满足 @NotBlank
        args.setJobParams("{\"count\":10}");

        assertThatThrownBy(() -> mockJobService.executeWithDto(args, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("参数校验失败");
    }

    @Test
    @DisplayName("测试参数非合法 JSON 格式反序列化时抛出 BusinessException")
    void testInvalidJsonThrowsBusinessException() {
        JobArgs args = new JobArgs();
        args.setJobParams("invalid-json-content");

        assertThatThrownBy(() -> mockJobService.executeWithDto(args, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("参数反序列化失败");
    }

    @Test
    @DisplayName("测试本地硬超时看门狗触发线程中断并广播 SnailJobTimeoutEvent")
    void testWatchdogTimeoutInterruptionAndEventBroadcast() throws InterruptedException {
        JobArgs args = new JobArgs();
        args.setJobId(777L);
        args.setTaskBatchId(888L);
        args.setExecutorInfo("testTimeoutJob");

        timeoutEventListener.resetLatch(1);

        mockJobService.executeWithTimeout(args);

        // 等待看门狗事件广播完成（时限 50ms 超时，等待至多 3 秒）
        boolean received = timeoutEventListener.await(3, TimeUnit.SECONDS);
        assertThat(received).isTrue();

        List<SnailJobTimeoutEvent> events = timeoutEventListener.getEvents();
        assertThat(events).hasSize(1);
        SnailJobTimeoutEvent event = events.get(0);
        assertThat(event.getJobId()).isEqualTo(777L);
        assertThat(event.getTaskBatchId()).isEqualTo(888L);
        assertThat(event.getTimeoutDuration()).isEqualTo(Duration.ofMillis(80));

        // 验证当前线程残留中断标记已在 finally 中确定性清理
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("测试 SnailJobContext 门面自动注入形参并双向写回 JobArgs")
    void testSnailJobContextInjectionAndSync() {
        JobArgs args = new JobArgs();
        args.setJobId(5001L);
        args.setWorkflowTaskBatchId(6001L);
        args.setJobParams("{\"orderId\":12345}");

        ExecuteResult result = mockJobService.executeWithContext(args, null);
        assertThat(result).isNotNull();
        assertThat(result.getResult()).isEqualTo("Context executed");

        // 验证切面退出后，在方法中 append 的变更上下文已成功同步至底层 JobArgs
        assertThat(args.getChangeWfContext()).containsEntry("workflowStatus", "STEP_1_DONE");
        assertThat(args.getChangeWfContext()).containsEntry("retryCount", 3);
    }

    @Test
    @DisplayName("测试 ShardingContext 自动注入形参并正确承载分片参数")
    void testShardingContextInjection() {
        ShardingJobArgs shardingArgs = new ShardingJobArgs();
        shardingArgs.setJobId(8001L);
        shardingArgs.setShardingIndex(2);
        shardingArgs.setShardingTotal(5);

        ExecuteResult result = mockJobService.executeWithSharding(shardingArgs, null);
        assertThat(result).isNotNull();
        assertThat(result.getResult()).isEqualTo("index: 2, total: 5");
    }

    @Test
    @DisplayName("测试 @MapExecutor 切片任务拦截、复合命名注入与 4D 上下文确定性清理")
    void testMapExecutorInterceptionAndCompositeNaming() {
        MDC.put(SnailJobContextAspect.TRACE_ID_KEY, "prev-trace-map");
        MDC.put(SnailJobContextAspect.TASK_NAME_KEY, "prev-task");
        UserContextHolder.set("prev-map-user");
        TenantContextHolder.setTenantId("prev-tenant");

        MapArgs mapArgs = new MapArgs();
        mapArgs.setJobId(3001L);
        mapArgs.setTaskBatchId(4001L);
        mapArgs.setTaskName("slice1");
        mapArgs.setJobParams("{\"traceId\":\"slice-trace-101\",\"userId\":\"slice-operator\"}");

        MapHandler<Object> mockHandler = (list, taskName) -> ExecuteResult.success();

        ExecuteResult result = mockMapReduceJobService.executeMap(mapArgs, mockHandler);
        assertThat(result).isNotNull();
        assertThat(result.getResult()).isEqualTo("slice1-done");

        // 验证 4D 上下文在 finally 中严格复原，无任何线程复用污染
        assertThat(MDC.get(SnailJobContextAspect.TRACE_ID_KEY)).isEqualTo("prev-trace-map");
        assertThat(MDC.get(SnailJobContextAspect.TASK_NAME_KEY)).isEqualTo("prev-task");
        assertThat(UserContextHolder.getExplicit()).isEqualTo("prev-map-user");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("prev-tenant");
    }

    @Test
    @DisplayName("测试 @ReduceExecutor 规约任务拦截与复合命名")
    void testReduceExecutorInterception() {
        ReduceArgs reduceArgs = new ReduceArgs();
        reduceArgs.setJobId(3002L);
        reduceArgs.setTaskBatchId(4002L);
        reduceArgs.setJobParams("{\"traceId\":\"reduce-trace-102\"}");

        ExecuteResult result = mockMapReduceJobService.executeReduce(reduceArgs);
        assertThat(result).isNotNull();
        assertThat(result.getResult()).isEqualTo("reduce-done");

        assertThat(MDC.get(SnailJobContextAspect.TASK_NAME_KEY)).isNull();
    }

    @Test
    @DisplayName("测试 @MergeReduceExecutor 合并规约任务拦截与复合命名")
    void testMergeReduceExecutorInterception() {
        MergeReduceArgs mergeArgs = new MergeReduceArgs();
        mergeArgs.setJobId(3003L);
        mergeArgs.setTaskBatchId(4003L);
        mergeArgs.setJobParams("{\"traceId\":\"merge-trace-103\"}");

        ExecuteResult result = mockMapReduceJobService.executeMergeReduce(mergeArgs);
        assertThat(result).isNotNull();
        assertThat(result.getResult()).isEqualTo("mergeReduce-done");

        assertThat(MDC.get(SnailJobContextAspect.TASK_NAME_KEY)).isNull();
    }

    @Test
    @DisplayName("测试独立切片类缺省根任务名时回退为 <ClassName>:<taskName>")
    void testStandaloneSliceFallbackNaming() {
        MapArgs mapArgs = new MapArgs();
        mapArgs.setTaskName("standaloneSlice");

        MapHandler<Object> mockHandler = (list, taskName) -> ExecuteResult.success();

        ExecuteResult result = standaloneSliceJobService.executeSlice(mapArgs, mockHandler);
        assertThat(result).isNotNull();
        assertThat(result.getResult()).isEqualTo("standalone-done");

        assertThat(MDC.get(SnailJobContextAspect.TASK_NAME_KEY)).isNull();
    }

    @Test
    @DisplayName("测试 Map 切片任务挂载 @Valid 参数强类型校验及 MapHandler 防御")
    void testMapExecutorParameterValidation() {
        MapArgs mapArgs = new MapArgs();
        mapArgs.setTaskName("sliceValidated");
        mapArgs.setJobParams("{\"name\":\"valid-slice-order\",\"count\":10}");

        MapHandler<Object> mockHandler = (list, taskName) -> ExecuteResult.success();

        // 1. 合法参数执行成功，MapHandler 不受干扰
        ExecuteResult successResult = mockMapReduceJobService.executeMapWithValidation(mapArgs, null, mockHandler);
        assertThat(successResult).isNotNull();
        assertThat(successResult.getResult()).isEqualTo("validated: valid-slice-order");

        // 2. 约束违规触发 JSR-303 Fail-Fast 异常
        MapArgs invalidArgs = new MapArgs();
        invalidArgs.setTaskName("sliceValidated");
        invalidArgs.setJobParams("{\"name\":\"invalid-order\",\"count\":0}");

        assertThatThrownBy(() -> mockMapReduceJobService.executeMapWithValidation(invalidArgs, null, mockHandler))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("参数校验失败");
    }

    @Test
    @DisplayName("测试 Map 切片任务受管本地硬看门狗超时中断与事件广播")
    void testMapExecutorWatchdogTimeout() throws InterruptedException {
        MapArgs mapArgs = new MapArgs();
        mapArgs.setJobId(9001L);
        mapArgs.setTaskBatchId(9002L);
        mapArgs.setTaskName("sliceTimeout");

        MapHandler<Object> mockHandler = (list, taskName) -> ExecuteResult.success();

        timeoutEventListener.resetLatch(1);

        mockMapReduceJobService.executeMapTimeout(mapArgs, mockHandler);

        boolean received = timeoutEventListener.await(3, TimeUnit.SECONDS);
        assertThat(received).isTrue();

        List<SnailJobTimeoutEvent> events = timeoutEventListener.getEvents();
        assertThat(events).hasSize(1);
        SnailJobTimeoutEvent event = events.get(0);
        assertThat(event.getJobId()).isEqualTo(9001L);
        assertThat(event.getTaskBatchId()).isEqualTo(9002L);
        assertThat(event.getJobName()).isEqualTo("mapReduceTestJob:sliceTimeout");

        // 验证当前线程残留中断标记已在 finally 中确定性清理
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("测试 SnailJobTestHelper 便捷触发 Map / Reduce / MergeReduce 切片任务")
    void testSnailJobTestHelperMapReduceTriggers() {
        // 1. triggerMap
        SnailJobTestResult mapResult = SnailJobTestHelper.triggerMap("mapReduceTestJob", "slice1", "{\"traceId\":\"tr-map\"}");
        assertThat(mapResult).isNotNull();
        assertThat(mapResult.isSuccess()).isTrue();
        assertThat(mapResult.traceId()).isNotEmpty();

        // 2. triggerReduce
        SnailJobTestResult reduceResult = SnailJobTestHelper.triggerReduce("mapReduceTestJob", "{\"traceId\":\"tr-red\"}");
        assertThat(reduceResult).isNotNull();
        assertThat(reduceResult.isSuccess()).isTrue();

        // 3. triggerMergeReduce
        SnailJobTestResult mergeResult = SnailJobTestHelper.triggerMergeReduce("mapReduceTestJob", "{\"traceId\":\"tr-mrg\"}");
        assertThat(mergeResult).isNotNull();
        assertThat(mergeResult.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("测试超过 maxPayloadSize 限制的 jobParams 在入口立即 Fail-Fast 拒绝且不执行业务")
    void testMaxPayloadSizeRejectsOversizedJobParamsFailFast() {
        io.github.atengk.boot.job.snail.properties.SnailJobProperties smallLimitProps =
                new io.github.atengk.boot.job.snail.properties.SnailJobProperties();
        smallLimitProps.setMaxPayloadSize(org.springframework.util.unit.DataSize.ofBytes(64));

        SnailJobContextAspect limitedAspect = new SnailJobContextAspect(null, smallLimitProps);
        org.springframework.aop.aspectj.annotation.AspectJProxyFactory proxyFactory =
                new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(new MockJobService());
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAspect(limitedAspect);
        MockJobService proxy = proxyFactory.getProxy();

        JobArgs oversized = new JobArgs();
        oversized.setJobParams("{\"name\":\"" + "X".repeat(128) + "\",\"count\":1}");

        assertThatThrownBy(() -> proxy.executeWithDto(oversized, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("maxPayloadSize");
    }

    @Test
    @DisplayName("测试 @AtengSnailJob 本地退避重试瞬态故障自愈后成功，且不发布告警")
    void testLocalRetryRecoversOnTransientFailureWithoutAlert() {
        JobArgs args = new JobArgs();
        args.setJobParams("{\"traceId\":\"tr-retry-ok\"}");

        ExecuteResult result = governanceJobService.retryableRecoverJob(args);
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(1);
        assertThat(governanceJobService.getAttemptCount()).isEqualTo(2);
        assertThat(timeoutEventListener.getAlertEvents()).isEmpty();
    }

    @Test
    @DisplayName("测试 @AtengSnailJob 本地重试耗尽后抛出异常并仅发布一次首发告警")
    void testLocalRetryExhaustedPublishesSingleAlert() {
        JobArgs args = new JobArgs();
        args.setJobParams("{\"traceId\":\"tr-retry-fail\"}");

        assertThatThrownBy(() -> governanceJobService.retryableExhaustJob(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persistent downstream glitch");

        assertThat(governanceJobService.getAttemptCount()).isEqualTo(3);
        assertThat(timeoutEventListener.getAlertEvents()).hasSize(1);
        assertThat(timeoutEventListener.getAlertEvents().get(0).getAlertType())
                .isEqualTo(SnailJobAlertEvent.AlertType.INITIAL_FAILURE);
    }

    @Test
    @DisplayName("测试校验失败、JSON 解析错误、方法内校验失败及看门狗硬超时均强制 0 次重试 Fail-Fast")
    void testDeterministicErrorsSkipLocalRetry() {
        // 1. DTO JSR-303 校验失败 -> 0 次进入目标方法
        JobArgs invalidDtoArgs = new JobArgs();
        invalidDtoArgs.setJobParams("{\"name\":\"\",\"count\":0}");
        assertThatThrownBy(() -> governanceJobService.retryableValidatedDtoJob(invalidDtoArgs, null))
                .isInstanceOf(BusinessException.class);
        assertThat(governanceJobService.getAttemptCount()).isZero();

        // 2. JSON 反序列化失败 -> 0 次进入目标方法
        JobArgs brokenJsonArgs = new JobArgs();
        brokenJsonArgs.setJobParams("{broken-json");
        assertThatThrownBy(() -> governanceJobService.retryableValidatedDtoJob(brokenJsonArgs, null))
                .isInstanceOf(BusinessException.class);
        assertThat(governanceJobService.getAttemptCount()).isZero();

        // 3. 方法体内 ValidatorUtil 抛出 BAD_REQUEST BusinessException -> 仅执行 1 次不重试
        assertThatThrownBy(() -> governanceJobService.retryableProgrammaticValidationJob(new JobArgs()))
                .isInstanceOf(BusinessException.class);
        assertThat(governanceJobService.getAttemptCount()).isEqualTo(1);

        // 4. 看门狗硬超时 -> 仅执行 1 次不重试
        governanceJobService.reset();
        assertThatThrownBy(() -> governanceJobService.retryableTimeoutJob(new JobArgs()))
                .isInstanceOf(InterruptedException.class);
        assertThat(governanceJobService.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("测试工作线程吞咽中断正常返回时看门狗仍强制判超时并触发 fallbackMethod / 全局降级与事件")
    void testSwallowedInterruptNormalReturnStillTriggersFallbackAndEvent() throws InterruptedException {
        timeoutEventListener.resetLatch(1);
        JobArgs args = new JobArgs();
        args.setJobId(9101L);
        args.setTaskBatchId(9102L);

        ExecuteResult result = governanceJobService.swallowInterruptWithFallback(args);
        assertThat(result).isNotNull();
        assertThat(result.getResult()).isEqualTo("forced-fallback-after-swallow");
        assertThat(timeoutEventListener.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(timeoutEventListener.getEvents()).hasSize(1);

        // 无 fallbackMethod 且吞咽中断返回虚假成功时：强制拒绝放行，触发全局降级并抛出超时异常
        assertThatThrownBy(() -> governanceJobService.swallowInterruptWithoutFallback(new JobArgs()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超时");
        assertThat(governanceJobService.getGlobalFallbackCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("测试 @AtengSnailJob alertEnabled=false 静音覆写及 alertSuppressThreshold 独立阈值覆写")
    void testAnnotationAlertEnabledAndSuppressThresholdOverride() {
        assertThatThrownBy(() -> governanceJobService.mutedJob(new JobArgs()))
                .isInstanceOf(RuntimeException.class);
        assertThat(timeoutEventListener.getAlertEvents()).isEmpty();

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> governanceJobService.thresholdOverrideJob(new JobArgs()))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(timeoutEventListener.getAlertEvents()).hasSize(2);
        assertThat(timeoutEventListener.getAlertEvents().get(1).getAlertType())
                .isEqualTo(SnailJobAlertEvent.AlertType.FAILURE_SUMMARY);
    }

    @Test
    @DisplayName("测试执行元数据在 DTO 租户提取后完整构建，且支持受管线程池 Core taskExecutor 调度")
    void testCompleteMetadataAfterArgResolutionAndManagedPool() {
        JobArgs args = new JobArgs();
        args.setJobId(7001L);
        args.setTaskBatchId(7002L);
        args.setWorkflowTaskBatchId(7003L);
        args.setWorkflowNodeId(7004L);
        args.setJobBizId("BIZ-7005");
        args.setJobParams("{\"name\":\"meta-order\",\"count\":2,\"tenantId\":\"tenant-meta\"}");

        ExecuteResult result = governanceJobService.metadataCheckJob(args, null);
        assertThat(result.getStatus()).isEqualTo(1);

        SnailJobExecutionMetadata meta = metadataInterceptor.getCapturedMetadata();
        assertThat(meta).isNotNull();
        assertThat(meta.getTenantId()).isEqualTo("tenant-meta");
        assertThat(meta.getWorkflowTaskBatchId()).isEqualTo(7003L);
        assertThat(meta.getWorkflowNodeId()).isEqualTo(7004L);
        assertThat(meta.getJobBizId()).isEqualTo("BIZ-7005");

        // 验证注入 Core 受管线程池时切面提交至受管工作线程并透传 SnailJobContext
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.aop.AopAutoConfiguration.class,
                        io.github.atengk.boot.core.autoconfigure.AtengAsyncAutoConfiguration.class,
                        AtengSnailJobAutoConfiguration.class
                ))
                .withBean(StandaloneGovernanceJobService.class)
                .run(ctx -> {
                    StandaloneGovernanceJobService proxyService = ctx.getBean(StandaloneGovernanceJobService.class);
                    ExecuteResult poolRes = proxyService.managedPoolJob(new JobArgs());
                    assertThat(poolRes.getStatus()).isEqualTo(1);
                    assertThat(proxyService.getLastWorkerThreadName()).doesNotContain("ForkJoinPool.commonPool");
                    assertThat(proxyService.isContextPropagatedInWorker()).isTrue();
                });
    }

    @Test
    @DisplayName("测试非法负 retryInterval 不泄漏在途计数且超时后先清中断再调度降级方法")
    void testNegativeRetryIntervalDoesNotLeakInFlightAndTimeoutClearsInterruptBeforeFallback() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.aop.AopAutoConfiguration.class,
                        AtengSnailJobAutoConfiguration.class
                ))
                .withBean(StandaloneGovernanceJobService.class)
                .run(ctx -> {
                    io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine drain =
                            ctx.getBean(io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine.class);
                    StandaloneGovernanceJobService proxyService = ctx.getBean(StandaloneGovernanceJobService.class);

                    assertThatThrownBy(() -> proxyService.negativeRetryIntervalJob(new JobArgs()))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("retryInterval");
                    assertThat(drain.getInFlightJobCount()).isZero();
                    assertThat(MDC.get(SnailJobContextAspect.TRACE_ID_KEY)).isNull();
                    assertThat(TenantContextHolder.getTenantId()).isNull();
                    assertThat(UserContextHolder.getExplicit()).isNull();
                });

        ExecuteResult fallbackResult = governanceJobService.interruptFlagFallbackJob(new JobArgs());
        assertThat(fallbackResult.getStatus()).isEqualTo(1);
        assertThat(fallbackResult.getResult()).isEqualTo("fallback-interrupt-cleared");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @TestConfiguration
    @EnableAspectJAutoProxy
    static class TestJobConfig {

        @Bean
        public MockJobService mockJobService() {
            return new MockJobService();
        }

        @Bean
        public MockMapReduceJobService mockMapReduceJobService() {
            return new MockMapReduceJobService();
        }

        @Bean
        public StandaloneSliceJobService standaloneSliceJobService() {
            return new StandaloneSliceJobService();
        }

        @Bean
        public StandaloneGovernanceJobService standaloneGovernanceJobService() {
            return new StandaloneGovernanceJobService();
        }

        @Bean
        public CapturingMetadataInterceptor capturingMetadataInterceptor() {
            return new CapturingMetadataInterceptor();
        }

        @Bean
        public TestTimeoutEventListener testTimeoutEventListener(StandaloneGovernanceJobService govService) {
            return new TestTimeoutEventListener(govService);
        }

        @Bean
        public JobTimeoutFallbackHandler globalCountingFallbackHandler(StandaloneGovernanceJobService govService) {
            return (metadata, elapsed) -> govService.incrementGlobalFallback();
        }
    }

    @Data
    public static class OrderTaskDto {
        @NotBlank(message = "任务名称不能为空")
        private String name;

        @Min(value = 1, message = "处理数量必须大于等于 1")
        private Integer count;

        private String tenantId;
    }

    @Service
    public static class MockJobService {

        @JobExecutor(name = "testNativeJob")
        public ExecuteResult executeNative(JobArgs args) {
            assertThat(MDC.get(SnailJobContextAspect.TRACE_ID_KEY)).isEqualTo("custom-trace-999");
            assertThat(UserContextHolder.getExplicit()).isEqualTo("admin");
            return ExecuteResult.success();
        }

        @JobExecutor(name = "testTenantIgnore")
        @AtengSnailJob(tenantMode = JobTenantMode.IGNORE)
        public ExecuteResult executeTenantIgnore(JobArgs args) {
            assertThat(TenantContextHolder.isIgnoreTenant()).isTrue();
            return ExecuteResult.success();
        }

        @JobExecutor(name = "testTenantParam")
        @AtengSnailJob(tenantMode = JobTenantMode.PARAM)
        public ExecuteResult executeTenantParam(JobArgs args) {
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-888");
            return ExecuteResult.success();
        }

        @JobExecutor(name = "testDtoJob")
        public ExecuteResult executeWithDto(JobArgs args, @Valid OrderTaskDto dto) {
            assertThat(dto).isNotNull();
            return ExecuteResult.success("order: " + dto.getName() + ", count: " + dto.getCount());
        }

        @JobExecutor(name = "testTimeoutJob")
        @AtengSnailJob(timeout = "80ms")
        public ExecuteResult executeWithTimeout(JobArgs args) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // 工作线程捕获到硬看门狗中断
                return ExecuteResult.failure(null, "interrupted");
            }
            return ExecuteResult.success();
        }

        @JobExecutor(name = "testContextJob")
        public ExecuteResult executeWithContext(JobArgs args, SnailJobContext ctx) {
            assertThat(ctx).isNotNull();
            assertThat(ctx.getJobId()).isEqualTo(5001L);
            assertThat(ctx.getWorkflowTaskBatchId()).isEqualTo(6001L);
            ctx.appendWfContext("workflowStatus", "STEP_1_DONE");
            ctx.appendWfContext("retryCount", 3);
            return ExecuteResult.success("Context executed");
        }

        @JobExecutor(name = "testShardingJob")
        public ExecuteResult executeWithSharding(JobArgs args, ShardingContext sharding) {
            assertThat(sharding).isNotNull();
            return ExecuteResult.success("index: " + sharding.getShardIndex() + ", total: " + sharding.getShardTotal());
        }
    }

    @Service
    @JobExecutor(name = "mapReduceTestJob")
    public static class MockMapReduceJobService {

        @MapExecutor(taskName = "slice1")
        public ExecuteResult executeMap(MapArgs args, MapHandler handler) {
            assertThat(SnailJobContext.current().getJobName()).isEqualTo("mapReduceTestJob:slice1");
            assertThat(SnailJobContext.current().getTaskName()).isEqualTo("slice1");
            assertThat(MDC.get(SnailJobContextAspect.TASK_NAME_KEY)).isEqualTo("slice1");
            assertThat(MDC.get(SnailJobContextAspect.TRACE_ID_KEY)).isNotEmpty();
            assertThat(handler).isNotNull();
            return ExecuteResult.success("slice1-done");
        }

        @MapExecutor(taskName = "sliceValidated")
        public ExecuteResult executeMapWithValidation(MapArgs args, @Valid OrderTaskDto dto, MapHandler handler) {
            assertThat(dto).isNotNull();
            assertThat(handler).isNotNull();
            return ExecuteResult.success("validated: " + dto.getName());
        }

        @ReduceExecutor
        public ExecuteResult executeReduce(ReduceArgs args) {
            assertThat(SnailJobContext.current().getJobName()).isEqualTo("mapReduceTestJob:reduce");
            assertThat(SnailJobContext.current().getTaskName()).isEqualTo("reduce");
            assertThat(MDC.get(SnailJobContextAspect.TASK_NAME_KEY)).isEqualTo("reduce");
            return ExecuteResult.success("reduce-done");
        }

        @MergeReduceExecutor
        public ExecuteResult executeMergeReduce(MergeReduceArgs args) {
            assertThat(SnailJobContext.current().getJobName()).isEqualTo("mapReduceTestJob:mergeReduce");
            assertThat(SnailJobContext.current().getTaskName()).isEqualTo("mergeReduce");
            assertThat(MDC.get(SnailJobContextAspect.TASK_NAME_KEY)).isEqualTo("mergeReduce");
            return ExecuteResult.success("mergeReduce-done");
        }

        @MapExecutor(taskName = "sliceTimeout")
        @AtengSnailJob(timeout = "80ms")
        public ExecuteResult executeMapTimeout(MapArgs args, MapHandler handler) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return ExecuteResult.failure(null, "slice-interrupted");
            }
            return ExecuteResult.success();
        }
    }

    @Service
    public static class StandaloneSliceJobService {

        @MapExecutor(taskName = "standaloneSlice")
        public ExecuteResult executeSlice(MapArgs args, MapHandler handler) {
            assertThat(SnailJobContext.current().getJobName()).isEqualTo("StandaloneSliceJobService:standaloneSlice");
            assertThat(SnailJobContext.current().getTaskName()).isEqualTo("standaloneSlice");
            assertThat(MDC.get(SnailJobContextAspect.TASK_NAME_KEY)).isEqualTo("standaloneSlice");
            return ExecuteResult.success("standalone-done");
        }
    }

    @Service
    public static class StandaloneGovernanceJobService {

        private final java.util.concurrent.atomic.AtomicInteger attemptCount = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger globalFallbackCount = new java.util.concurrent.atomic.AtomicInteger();
        private volatile String lastWorkerThreadName;
        private volatile boolean contextPropagatedInWorker;

        public void reset() {
            attemptCount.set(0);
            globalFallbackCount.set(0);
            lastWorkerThreadName = null;
            contextPropagatedInWorker = false;
        }

        public int getAttemptCount() {
            return attemptCount.get();
        }

        public int getGlobalFallbackCount() {
            return globalFallbackCount.get();
        }

        public void incrementGlobalFallback() {
            globalFallbackCount.incrementAndGet();
        }

        public String getLastWorkerThreadName() {
            return lastWorkerThreadName;
        }

        public boolean isContextPropagatedInWorker() {
            return contextPropagatedInWorker;
        }

        @AtengSnailJob(name = "localRetryRecoverJob", retryCount = 2, retryInterval = "10ms")
        public ExecuteResult retryableRecoverJob(JobArgs args) {
            int attempt = attemptCount.incrementAndGet();
            if (attempt == 1) {
                throw new IllegalStateException("transient network glitch");
            }
            return ExecuteResult.success("recovered");
        }

        @AtengSnailJob(name = "localRetryExhaustJob", retryCount = 2, retryInterval = "10ms")
        public ExecuteResult retryableExhaustJob(JobArgs args) {
            attemptCount.incrementAndGet();
            throw new IllegalStateException("persistent downstream glitch");
        }

        @AtengSnailJob(name = "retryValidatedDtoJob", retryCount = 3, retryInterval = "10ms")
        public ExecuteResult retryableValidatedDtoJob(JobArgs args, @Valid OrderTaskDto dto) {
            attemptCount.incrementAndGet();
            return ExecuteResult.success(dto.getName());
        }

        @AtengSnailJob(name = "retryProgValidationJob", retryCount = 3, retryInterval = "10ms")
        public ExecuteResult retryableProgrammaticValidationJob(JobArgs args) {
            attemptCount.incrementAndGet();
            OrderTaskDto invalid = new OrderTaskDto();
            ValidatorUtil.validate(invalid);
            return ExecuteResult.success();
        }

        @AtengSnailJob(name = "retryTimeoutJob", timeout = "50ms", retryCount = 3, retryInterval = "10ms")
        public ExecuteResult retryableTimeoutJob(JobArgs args) throws InterruptedException {
            attemptCount.incrementAndGet();
            Thread.sleep(300);
            return ExecuteResult.success();
        }

        @AtengSnailJob(name = "swallowInterruptFallbackJob", timeout = "60ms", fallbackMethod = "onSwallowTimeout")
        public ExecuteResult swallowInterruptWithFallback(JobArgs args) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                // 模拟业务代码吞咽中断并试图正常返回
            }
            return ExecuteResult.success("fake-ok");
        }

        public ExecuteResult onSwallowTimeout() {
            return ExecuteResult.success("forced-fallback-after-swallow");
        }

        @AtengSnailJob(name = "interruptFlagFallbackJob", timeout = "50ms", fallbackMethod = "onInterruptFlagFallback")
        public ExecuteResult interruptFlagFallbackJob(JobArgs args) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(400);
            while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            // 不主动清除中断标志，直接带中断状态返回，验证切面调用降级前已清理中断位
            return ExecuteResult.success("fake-ok");
        }

        public ExecuteResult onInterruptFlagFallback() {
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            try {
                Thread.sleep(5);
            } catch (InterruptedException ex) {
                throw new IllegalStateException("fallback interrupted unexpectedly", ex);
            }
            return ExecuteResult.success("fallback-interrupt-cleared");
        }

        @AtengSnailJob(name = "negativeRetryIntervalJob", retryCount = 1, retryInterval = "-10ms")
        public ExecuteResult negativeRetryIntervalJob(JobArgs args) {
            return ExecuteResult.success();
        }

        @AtengSnailJob(name = "swallowInterruptNoFallbackJob", timeout = "60ms")
        public ExecuteResult swallowInterruptWithoutFallback(JobArgs args) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                // 吞咽中断返回成功
            }
            return ExecuteResult.success("fake-ok");
        }

        @AtengSnailJob(name = "mutedProbeJob", alertEnabled = false)
        public ExecuteResult mutedJob(JobArgs args) {
            throw new RuntimeException("noisy failure");
        }

        @AtengSnailJob(name = "thresholdOverrideJob", alertEnabled = true, alertSuppressThreshold = 2)
        public ExecuteResult thresholdOverrideJob(JobArgs args) {
            throw new RuntimeException("threshold failure");
        }

        @AtengSnailJob(name = "metadataCheckJob", tenantMode = JobTenantMode.PARAM)
        public ExecuteResult metadataCheckJob(JobArgs args, @Valid OrderTaskDto dto) {
            return ExecuteResult.success(dto.getTenantId());
        }

        @AtengSnailJob(name = "managedPoolJob")
        public ExecuteResult managedPoolJob(JobArgs args) {
            this.lastWorkerThreadName = Thread.currentThread().getName();
            this.contextPropagatedInWorker = SnailJobContextHolder.get() != null
                    && org.springframework.util.StringUtils.hasText(MDC.get(SnailJobContextAspect.TRACE_ID_KEY));
            return ExecuteResult.success("pool-ok");
        }
    }

    public static class CapturingMetadataInterceptor implements SnailJobInterceptor {

        private volatile SnailJobExecutionMetadata capturedMetadata;

        @Override
        public boolean preHandle(SnailJobExecutionContext context) {
            this.capturedMetadata = context.getMetadata();
            return true;
        }

        public SnailJobExecutionMetadata getCapturedMetadata() {
            return capturedMetadata;
        }

        public void clear() {
            this.capturedMetadata = null;
        }
    }

    public static class TestTimeoutEventListener {

        private final List<SnailJobTimeoutEvent> events = Collections.synchronizedList(new ArrayList<>());
        private final List<SnailJobAlertEvent> alertEvents = Collections.synchronizedList(new ArrayList<>());
        private volatile CountDownLatch latch = new CountDownLatch(1);

        public TestTimeoutEventListener(StandaloneGovernanceJobService govService) {
        }

        public void resetLatch(int count) {
            this.latch = new CountDownLatch(count);
        }

        public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        @EventListener
        public void onTimeout(SnailJobTimeoutEvent event) {
            events.add(event);
            latch.countDown();
        }

        @EventListener
        public void onAlert(SnailJobAlertEvent event) {
            alertEvents.add(event);
        }

        public List<SnailJobTimeoutEvent> getEvents() {
            return new ArrayList<>(events);
        }

        public List<SnailJobAlertEvent> getAlertEvents() {
            return new ArrayList<>(alertEvents);
        }

        public void clear() {
            events.clear();
            alertEvents.clear();
        }
    }
}
