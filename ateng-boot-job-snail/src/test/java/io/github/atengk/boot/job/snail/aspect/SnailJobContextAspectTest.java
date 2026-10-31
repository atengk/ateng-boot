package io.github.atengk.boot.job.snail.aspect;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.autoconfigure.AtengSnailJobAutoConfiguration;
import io.github.atengk.boot.job.snail.enums.JobTenantMode;
import io.github.atengk.boot.job.snail.event.SnailJobTimeoutEvent;
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
    private TestTimeoutEventListener timeoutEventListener;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
        timeoutEventListener.clear();
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

    @TestConfiguration
    @EnableAspectJAutoProxy
    static class TestJobConfig {

        @Bean
        public MockJobService mockJobService() {
            return new MockJobService();
        }

        @Bean
        public TestTimeoutEventListener testTimeoutEventListener() {
            return new TestTimeoutEventListener();
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
    }

    public static class TestTimeoutEventListener {

        private final List<SnailJobTimeoutEvent> events = Collections.synchronizedList(new ArrayList<>());
        private volatile CountDownLatch latch = new CountDownLatch(1);

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

        public List<SnailJobTimeoutEvent> getEvents() {
            return new ArrayList<>(events);
        }

        public void clear() {
            events.clear();
        }
    }
}
