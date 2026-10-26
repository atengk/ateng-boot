package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.enums.TransactionCompletionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TransactionUtil 事务生命周期门面单元测试
 * 覆盖提交/回滚生命周期、无事务降级、异步四维现场快照透传与异常受控隔离
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("TransactionUtil 事务同步生命周期门面测试")
class TransactionUtilTest {

    @Configuration
    static class TestConfig {
        @Bean(name = "taskExecutor")
        public ExecutorService taskExecutor() {
            return Executors.newFixedThreadPool(2);
        }
    }

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        SpringUtil.clear();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }

        context = new AnnotationConfigApplicationContext(TestConfig.class);
        SpringUtil.setContext(context);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        if (context != null) {
            context.close();
        }
        SpringUtil.clear();
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("前置参数防卫: null 入参触发 Fail-Fast")
    void shouldThrowWhenParameterIsNull() {
        assertThrows(NullPointerException.class, () -> TransactionUtil.executeBeforeCommit(null));
        assertThrows(NullPointerException.class, () -> TransactionUtil.executeAfterCommit(null));
        assertThrows(NullPointerException.class, () -> TransactionUtil.executeAfterRollback(null));
        assertThrows(NullPointerException.class, () -> TransactionUtil.executeAfterCompletion(null));
        assertThrows(NullPointerException.class, () -> TransactionUtil.executeAfterCommitAsync(null));
        assertThrows(NullPointerException.class, () -> TransactionUtil.publishAfterCommit(null));
    }

    @Test
    @DisplayName("非事务环境降级: executeAfterCommit 就地同步执行")
    void shouldExecuteAfterCommitImmediatelyWhenNoTransaction() {
        assertFalse(TransactionUtil.isSynchronizationActive());
        assertFalse(TransactionUtil.isActualTransactionActive());

        AtomicBoolean executed = new AtomicBoolean(false);
        TransactionUtil.executeAfterCommit(() -> executed.set(true));

        assertTrue(executed.get(), "非事务环境下 executeAfterCommit 应就地同步降级执行");
    }

    @Test
    @DisplayName("非事务环境降级: executeAfterRollback 安全跳过")
    void shouldSkipAfterRollbackWhenNoTransaction() {
        assertFalse(TransactionUtil.isSynchronizationActive());

        AtomicBoolean executed = new AtomicBoolean(false);
        TransactionUtil.executeAfterRollback(() -> executed.set(true));

        assertFalse(executed.get(), "非事务环境下 executeAfterRollback 应安全跳过，不得触发");
    }

    @Test
    @DisplayName("事务环境: executeAfterCommit 在事务提交时触发，回滚时不触发")
    void shouldExecuteAfterCommitWhenTransactionCommitted() {
        TransactionSynchronizationManager.initSynchronization();
        assertTrue(TransactionUtil.isSynchronizationActive());

        AtomicBoolean executed = new AtomicBoolean(false);
        TransactionUtil.executeAfterCommit(() -> executed.set(true));

        assertFalse(executed.get(), "注册后事务未提交前不得提前执行");

        // 模拟事务提交触发
        TransactionSynchronizationUtils.triggerAfterCommit();
        assertTrue(executed.get(), "事务提交后应触发 executeAfterCommit 任务");

        // 清理同步现场
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("事务环境: executeAfterRollback 在事务回滚时触发，提交时不触发")
    void shouldExecuteAfterRollbackWhenTransactionRolledBack() {
        TransactionSynchronizationManager.initSynchronization();
        assertTrue(TransactionUtil.isSynchronizationActive());

        AtomicBoolean rollbackExecuted = new AtomicBoolean(false);
        TransactionUtil.executeAfterRollback(() -> rollbackExecuted.set(true));

        assertFalse(rollbackExecuted.get(), "注册后事务未结束前不得提前执行");

        // 模拟事务提交完成（非回滚）
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        assertFalse(rollbackExecuted.get(), "事务提交时不应触发回滚回调");

        // 再次测试回滚场景
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();

        AtomicBoolean realRollback = new AtomicBoolean(false);
        TransactionUtil.executeAfterRollback(() -> realRollback.set(true));

        // 模拟事务回滚完成
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertTrue(realRollback.get(), "事务回滚后应触发 executeAfterRollback 任务");

        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("异常隔离: 回调任务发生异常受控记录 WARN，杜绝破坏主流程")
    void shouldIsolateExceptionsInCallbacks() {
        TransactionSynchronizationManager.initSynchronization();

        AtomicBoolean secondTaskExecuted = new AtomicBoolean(false);
        // 第一个任务抛出异常
        TransactionUtil.executeAfterCommit(() -> {
            throw new RuntimeException("模拟提交回调异常");
        });
        // 第二个任务正常执行
        TransactionUtil.executeAfterCommit(() -> secondTaskExecuted.set(true));

        // 触发提交回调，不应向外抛出异常
        assertDoesNotThrow(TransactionSynchronizationUtils::triggerAfterCommit);
        assertTrue(secondTaskExecuted.get(), "即使前序任务异常，后续任务应不受干扰继续执行");

        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("非事务环境异常隔离: 直接同步执行时异常亦受控隔离")
    void shouldIsolateExceptionInDirectExecution() {
        assertFalse(TransactionUtil.isSynchronizationActive());

        assertDoesNotThrow(() -> TransactionUtil.executeAfterCommit(() -> {
            throw new IllegalStateException("非事务同步异常");
        }));
    }

    @Test
    @DisplayName("异步提交回调: executeAfterCommitAsync 四维上下文完整透传与执行")
    void shouldPropagate4DContextInAsyncCommit() throws Exception {
        // 设置当前线程四维上下文
        MDC.put("traceId", "trace-tx-async-123");
        UserContextHolder.set("user-tx-async-456");
        TenantContextHolder.setTenantId("tenant-tx-async-789");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> asyncTraceId = new AtomicReference<>();
        AtomicReference<String> asyncUser = new AtomicReference<>();
        AtomicReference<String> asyncTenant = new AtomicReference<>();

        TransactionSynchronizationManager.initSynchronization();

        TransactionUtil.executeAfterCommitAsync(() -> {
            try {
                asyncTraceId.set(MDC.get("traceId"));
                asyncUser.set(UserContextHolder.get());
                asyncTenant.set(TenantContextHolder.getTenantId());
            } finally {
                latch.countDown();
            }
        });

        // 提交前尚未触发
        assertEquals(1, latch.getCount());

        // 模拟事务提交触发
        TransactionSynchronizationUtils.triggerAfterCommit();

        // 等待异步任务执行完成
        assertTrue(latch.await(3, TimeUnit.SECONDS), "异步任务应在指定时间内完成");
        assertEquals("trace-tx-async-123", asyncTraceId.get(), "异步线程应继承 MDC traceId");
        assertEquals("user-tx-async-456", asyncUser.get(), "异步线程应继承 User 身份");
        assertEquals("tenant-tx-async-789", asyncTenant.get(), "异步线程应继承 Tenant 租户");

        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("非事务环境 executeAfterCommitAsync: 就地异步调度并透传四维上下文")
    void shouldExecuteAsyncImmediatelyWhenNoTransaction() throws Exception {
        MDC.put("traceId", "trace-direct-async");
        UserContextHolder.set("user-direct-async");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> asyncTraceId = new AtomicReference<>();
        AtomicReference<String> asyncUser = new AtomicReference<>();

        TransactionUtil.executeAfterCommitAsync(() -> {
            try {
                asyncTraceId.set(MDC.get("traceId"));
                asyncUser.set(UserContextHolder.get());
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals("trace-direct-async", asyncTraceId.get());
        assertEquals("user-direct-async", asyncUser.get());
    }

    @Test
    @DisplayName("publishAfterCommit: 事务提交后发布 Spring 领域事件")
    void shouldPublishEventAfterCommit() {
        AtomicReference<String> receivedEvent = new AtomicReference<>();
        context.addApplicationListener(event -> {
            if (event instanceof org.springframework.context.PayloadApplicationEvent<?> payload) {
                if (payload.getPayload() instanceof String msg) {
                    receivedEvent.set(msg);
                }
            }
        });

        TransactionSynchronizationManager.initSynchronization();

        TransactionUtil.publishAfterCommit("order-paid-event");
        assertNull(receivedEvent.get(), "事务提交前不得发布事件");

        TransactionSynchronizationUtils.triggerAfterCommit();
        assertEquals("order-paid-event", receivedEvent.get(), "事务提交后应成功收到发布的事件");

        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("publishAfterCommit: 非事务环境下就地发布事件")
    void shouldPublishEventImmediatelyWhenNoTransaction() {
        AtomicReference<String> receivedEvent = new AtomicReference<>();
        context.addApplicationListener(event -> {
            if (event instanceof org.springframework.context.PayloadApplicationEvent<?> payload) {
                if (payload.getPayload() instanceof String msg) {
                    receivedEvent.set(msg);
                }
            }
        });

        TransactionUtil.publishAfterCommit("order-direct-event");
        assertEquals("order-direct-event", receivedEvent.get());
    }

    @Test
    @DisplayName("executeBeforeCommit: 事务环境前置校验与异常阻断事务")
    void shouldExecuteBeforeCommitAndAbortOnException() {
        TransactionSynchronizationManager.initSynchronization();
        assertTrue(TransactionUtil.isSynchronizationActive());

        AtomicBoolean executed = new AtomicBoolean(false);
        TransactionUtil.executeBeforeCommit(() -> executed.set(true));

        assertFalse(executed.get(), "注册后未触发 beforeCommit 前不得提前执行");

        // 模拟 Spring 触发 beforeCommit
        TransactionSynchronizationUtils.triggerBeforeCommit(false);
        assertTrue(executed.get(), "beforeCommit 阶段任务应成功触发");

        // 异常阻断事务测试
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();

        TransactionUtil.executeBeforeCommit(() -> {
            throw new IllegalStateException("前置数据校验未通过，熔断事务");
        });

        // 验证异常不会被吞咽，而是向外抛出阻断事务提交
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> TransactionSynchronizationUtils.triggerBeforeCommit(false));
        assertEquals("前置数据校验未通过，熔断事务", ex.getMessage());

        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("executeBeforeCommit: 非事务环境下就地同步执行，异常直接抛出")
    void shouldExecuteBeforeCommitDirectlyWhenNoTransaction() {
        assertFalse(TransactionUtil.isSynchronizationActive());

        AtomicBoolean executed = new AtomicBoolean(false);
        TransactionUtil.executeBeforeCommit(() -> executed.set(true));
        assertTrue(executed.get(), "非事务环境下 executeBeforeCommit 应就地同步执行");

        // 异常直接向上抛出
        assertThrows(IllegalArgumentException.class, () ->
                TransactionUtil.executeBeforeCommit(() -> {
                    throw new IllegalArgumentException("非事务校验失败");
                }));
    }

    @Test
    @DisplayName("executeAfterCompletion: 事务全生命周期完成状态流转透传与异常隔离")
    void shouldExecuteAfterCompletionWithStatus() {
        // 1. 事务提交状态透传
        TransactionSynchronizationManager.initSynchronization();
        AtomicReference<TransactionCompletionStatus> committedStatus = new AtomicReference<>();
        TransactionUtil.executeAfterCompletion(committedStatus::set);

        assertNull(committedStatus.get(), "未完成前不得提前执行");
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        assertEquals(TransactionCompletionStatus.COMMITTED, committedStatus.get(), "应正确收到 COMMITTED 状态");
        TransactionSynchronizationManager.clearSynchronization();

        // 2. 事务回滚状态透传
        TransactionSynchronizationManager.initSynchronization();
        AtomicReference<TransactionCompletionStatus> rollbackStatus = new AtomicReference<>();
        TransactionUtil.executeAfterCompletion(rollbackStatus::set);

        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertEquals(TransactionCompletionStatus.ROLLED_BACK, rollbackStatus.get(), "应正确收到 ROLLED_BACK 状态");
        TransactionSynchronizationManager.clearSynchronization();

        // 3. 事务未知状态透传
        TransactionSynchronizationManager.initSynchronization();
        AtomicReference<TransactionCompletionStatus> unknownStatus = new AtomicReference<>();
        TransactionUtil.executeAfterCompletion(unknownStatus::set);

        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
        assertEquals(TransactionCompletionStatus.UNKNOWN, unknownStatus.get(), "应正确收到 UNKNOWN 状态");
        TransactionSynchronizationManager.clearSynchronization();

        // 4. 回调异常受控隔离
        TransactionSynchronizationManager.initSynchronization();
        TransactionUtil.executeAfterCompletion(status -> {
            throw new RuntimeException("清理回调次生异常");
        });
        assertDoesNotThrow(() -> TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        TransactionSynchronizationManager.clearSynchronization();

        // 5. 非事务环境降级执行并透传 COMMITTED
        assertFalse(TransactionUtil.isSynchronizationActive());
        AtomicReference<TransactionCompletionStatus> directStatus = new AtomicReference<>();
        TransactionUtil.executeAfterCompletion(directStatus::set);
        assertEquals(TransactionCompletionStatus.COMMITTED, directStatus.get(), "非事务降级应透传 COMMITTED");
    }
}
