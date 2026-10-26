package io.github.atengk.boot.sample.core.service;

import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.enums.TransactionCompletionStatus;
import io.github.atengk.boot.core.model.Pair;
import io.github.atengk.boot.core.model.ThreadPoolStatus;
import io.github.atengk.boot.core.util.AsyncUtil;
import io.github.atengk.boot.core.util.TransactionUtil;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core 场景一：上下文与异步透传服务
 * 演示并自验 UserContextHolder、TenantContextHolder、AsyncContext 四维上下文传递及 NamedThreadFactory
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Slf4j
@Service
public class CoreContextScenarioService {

    private final Executor taskExecutor;

    public CoreContextScenarioService(@Qualifier("taskExecutor") Executor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    /**
     * 1. 闭包式执行用户上下文业务，执行后自动安全清理现场
     *
     * @param userId 用户 ID
     * @return 闭包内部读取到的用户 ID
     */
    public String executeInUserContext(String userId) {
        return UserContextHolder.runWithUser(userId, () -> {
            String currentUserId = UserContextHolder.getUserId();
            log.info("当前闭包上下文中的用户 ID: {}", currentUserId);
            return currentUserId;
        });
    }

    /**
     * 2. 闭包式执行租户上下文业务，执行后自动还原原有租户
     *
     * @param tenantId 租户 ID
     * @return 闭包内部读取到的租户 ID
     */
    public String executeInTenantContext(String tenantId) {
        return TenantContextHolder.runWithTenant(tenantId, () -> {
            String currentTenantId = TenantContextHolder.getTenantId();
            log.info("当前闭包上下文中的租户 ID: {}", currentTenantId);
            return currentTenantId;
        });
    }

    /**
     * 3. 闭包式受控忽略租户拦截（如跨租户全局统计），执行后恢复租户过滤
     *
     * @return 闭包内部是否处于忽略租户状态
     */
    public boolean executeWithoutTenant() {
        return TenantContextHolder.runWithoutTenant(() -> {
            boolean isIgnore = TenantContextHolder.isIgnoreTenant();
            log.info("当前闭包上下文是否忽略租户过滤: {}", isIgnore);
            return isIgnore;
        });
    }

    /**
     * 4. 跨受管线程池异步透传四维上下文快照：MDC(traceId), User, TenantId, IgnoreTenant
     *
     * @param traceId  链路追踪 ID
     * @param userId   用户 ID
     * @param tenantId 租户 ID
     * @return 异步工作线程内部抓取到的上下文镜像
     */
    public CompletableFuture<Map<String, String>> propagateContextAsync(String traceId, String userId, String tenantId) {
        // 1. 在主调用线程注入四维上下文
        MDC.put("traceId", traceId);
        UserContextHolder.setUserId(userId);
        TenantContextHolder.setTenantId(tenantId);

        // 2. 调度受管 taskExecutor 线程池执行（由 ContextCopyingTaskDecorator 自动包装）
        return CompletableFuture.supplyAsync(() -> {
            // 工作线程中自动还原四维上下文
            String asyncTraceId = MDC.get("traceId");
            String asyncUserId = UserContextHolder.getUserId();
            String asyncTenantId = TenantContextHolder.getTenantId();
            boolean asyncIgnore = TenantContextHolder.isIgnoreTenant();

            log.info("异步工作线程捕获四维上下文: traceId={}, userId={}, tenantId={}, ignoreTenant={}",
                    asyncTraceId, asyncUserId, asyncTenantId, asyncIgnore);

            return Map.of(
                    "traceId", asyncTraceId != null ? asyncTraceId : "",
                    "userId", asyncUserId != null ? asyncUserId : "",
                    "tenantId", asyncTenantId != null ? asyncTenantId : "",
                    "ignoreTenant", String.valueOf(asyncIgnore)
            );
        }, taskExecutor);
    }

    /**
     * 5. Spring @Async 注解结合 ContextCopyingTaskDecorator 透传上下文
     *
     * @param traceId 链路 ID
     * @param userId  用户 ID
     * @return CompletableFuture 结果
     */
    @Async("taskExecutor")
    public CompletableFuture<Pair<String, String>> executeWithSpringAsync(String traceId, String userId) {
        String currentTraceId = MDC.get("traceId");
        String currentUserId = UserContextHolder.getUserId();
        log.info("Spring @Async 工作线程接收到上下文: traceId={}, userId={}", currentTraceId, currentUserId);
        return CompletableFuture.completedFuture(Pair.of(currentTraceId, currentUserId));
    }

    /**
     * 6. 使用 NamedThreadFactory 构建可观测命名线程池
     *
     * @param poolName 线程池名称前缀
     * @return 线程执行时实际名称与守护状态
     */
    public Pair<String, Boolean> demonstrateNamedThreadFactory(String poolName) {
        NamedThreadFactory factory = new NamedThreadFactory(poolName, false);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadFactory(factory);
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        try {
            return executor.submit(() -> Pair.of(Thread.currentThread().getName(), Thread.currentThread().isDaemon())).get();
        } catch (Exception e) {
            throw new IllegalStateException("受管线程池执行失败", e);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 7. 事务同步门面与自适应平滑降级（无事务上下文就地同步/异步执行）
     *
     * @return 记录执行状态的标志对
     */
    public Pair<Boolean, Boolean> demonstrateTransactionHooks() {
        AtomicBoolean commitExecuted = new AtomicBoolean(false);
        AtomicBoolean rollbackExecuted = new AtomicBoolean(false);

        // 无活跃事务时降级为就地执行
        TransactionUtil.executeAfterCommit(() -> commitExecuted.set(true));
        // 无活跃事务时安全跳过回滚回调
        TransactionUtil.executeAfterRollback(() -> rollbackExecuted.set(true));

        return Pair.of(commitExecuted.get(), rollbackExecuted.get());
    }

    /**
     * 8. 演示 AsyncUtil 统一门面调度受管线程池并透明透传四维上下文快照
     *
     * @param traceId  链路追踪 ID
     * @param userId   用户 ID
     * @param tenantId 租户 ID
     * @return 异步工作线程内部抓取到的上下文镜像
     */
    public CompletableFuture<Map<String, String>> executeWithAsyncUtil(String traceId, String userId, String tenantId) {
        MDC.put("traceId", traceId);
        UserContextHolder.setUserId(userId);
        TenantContextHolder.setTenantId(tenantId);

        return AsyncUtil.supply("export", () -> {
            String currentThread = Thread.currentThread().getName();
            String asyncTraceId = MDC.get("traceId");
            String asyncUserId = UserContextHolder.getUserId();
            String asyncTenantId = TenantContextHolder.getTenantId();
            boolean asyncIgnore = TenantContextHolder.isIgnoreTenant();

            log.info("AsyncUtil 异步调度成功 [thread={}, traceId={}, userId={}, tenantId={}, ignoreTenant={}]",
                    currentThread, asyncTraceId, asyncUserId, asyncTenantId, asyncIgnore);

            return Map.of(
                    "threadName", currentThread,
                    "traceId", asyncTraceId != null ? asyncTraceId : "",
                    "userId", asyncUserId != null ? asyncUserId : "",
                    "tenantId", asyncTenantId != null ? asyncTenantId : "",
                    "ignoreTenant", String.valueOf(asyncIgnore)
            );
        });
    }

    /**
     * 9. 演示 ThreadPoolStatus 实时全景指标快照探测与 AsyncUtil.resize 在线动态调参
     *
     * @param poolName 目标线程池名称
     * @param newCore  调整后的核心线程数
     * @param newMax   调整后的最大线程数
     * @return 调参前后的监控指标快照对
     */
    public Pair<ThreadPoolStatus, ThreadPoolStatus> demonstrateThreadPoolGovernance(String poolName, int newCore, int newMax) {
        ThreadPoolStatus before = AsyncUtil.getStatus(poolName);
        log.info("线程池 [{}] 调参前监控状态: {}", poolName, before);

        AsyncUtil.resize(poolName, newCore, newMax);
        ThreadPoolStatus after = AsyncUtil.getStatus(poolName);
        log.info("线程池 [{}] 调参后监控状态: {}", poolName, after);

        return Pair.of(before, after);
    }

    /**
     * 10. 演示 TransactionUtil 提交前校验阻断与终态全状态机钩子平滑降级
     *
     * @return 包含提交前与终态回调状态的数据对
     */
    public Pair<Boolean, TransactionCompletionStatus> demonstrateFullTransactionLifecycle() {
        AtomicBoolean beforeCommitExecuted = new AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<TransactionCompletionStatus> completionStatus = new java.util.concurrent.atomic.AtomicReference<>();

        TransactionUtil.executeBeforeCommit(() -> beforeCommitExecuted.set(true));
        TransactionUtil.executeAfterCompletion(completionStatus::set);

        return Pair.of(beforeCommitExecuted.get(), completionStatus.get());
    }

    /**
     * 11. 演示 ThreadPoolStatus 虚拟线程感知与自适应指标探测
     *
     * @param poolName 线程池名称
     * @return 线程池监控状态快照
     */
    public ThreadPoolStatus demonstrateVirtualThreadAwareness(String poolName) {
        ThreadPoolStatus status = AsyncUtil.getStatus(poolName);
        log.info("线程池 [{}] 虚拟线程状态探测: isVirtual={}, concurrencyLimit={}, activeCount={}",
                poolName, status.virtual(), status.concurrencyLimit(), status.activeCount());
        return status;
    }
}
