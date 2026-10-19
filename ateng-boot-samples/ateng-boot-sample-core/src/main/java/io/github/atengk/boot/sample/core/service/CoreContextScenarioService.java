package io.github.atengk.boot.sample.core.service;

import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Pair;
import io.github.atengk.boot.core.util.TransactionUtil;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        ExecutorService customPool = Executors.newSingleThreadExecutor(factory);
        try {
            return customPool.submit(() -> Pair.of(Thread.currentThread().getName(), Thread.currentThread().isDaemon())).get();
        } catch (Exception e) {
            throw new IllegalStateException("自定义线程池执行失败", e);
        } finally {
            customPool.shutdown();
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
}
