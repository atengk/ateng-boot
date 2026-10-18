package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.context.AsyncContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ClassUtils;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * 事务同步生命周期全局静态门面
 * 支持在数据库物理事务提交后执行同步/异步任务或发布领域事件，在事务回滚后执行补偿清理逻辑；
 * 具备类探测与无事务判定机制，无活跃事务或缺少事务依赖时平滑降级就地同步执行；
 * 异步回调自动调度受管线程池并透传四维上下文快照（MDC、User、TenantId、IgnoreTenant），回调异常受控隔离杜绝冲垮主流程
 *
 * @author Ateng
 * @since 2026-10-18
 */
public final class TransactionUtil {

    private static final Logger log = LoggerFactory.getLogger(TransactionUtil.class);

    /**
     * 探测 Spring Transaction 基础设施是否存在于当前运行类路径
     */
    private static final boolean SPRING_TX_PRESENT = ClassUtils.isPresent(
            "org.springframework.transaction.support.TransactionSynchronizationManager",
            TransactionUtil.class.getClassLoader()
    );

    private TransactionUtil() {
    }

    /**
     * 判定当前线程是否存在实际激活的物理数据库事务
     *
     * @return 若当前处于激活的物理事务中返回 true，否则返回 false
     */
    public static boolean isActualTransactionActive() {
        if (!SPRING_TX_PRESENT) {
            return false;
        }
        return SpringTransactionProvider.isActualTransactionActive();
    }

    /**
     * 判定当前线程是否已激活 Spring 事务同步器（TransactionSynchronization）
     *
     * @return 若事务同步器处于激活状态返回 true，否则返回 false
     */
    public static boolean isSynchronizationActive() {
        if (!SPRING_TX_PRESENT) {
            return false;
        }
        return SpringTransactionProvider.isSynchronizationActive();
    }

    /**
     * 在事务成功提交后同步执行指定任务
     * 若当前处于非事务上下文或未激活事务同步器，则平滑降级为就地立即同步执行；
     * 任务执行异常会被受控隔离并记录 WARN 日志，绝对不会逆向阻断或破坏主流程
     *
     * @param task 待执行任务，不可为 null
     */
    public static void executeAfterCommit(Runnable task) {
        Objects.requireNonNull(task, "task 不能为空");
        if (isSynchronizationActive()) {
            SpringTransactionProvider.registerAfterCommit(task);
        } else {
            log.debug("当前处于非事务上下文或未激活事务同步器，executeAfterCommit 就地同步执行");
            safeRun(task, "afterCommit[Direct]");
        }
    }

    /**
     * 在事务回滚后同步执行指定清理或补偿任务
     * 若当前处于非事务上下文或未激活事务同步器，因不存在回滚阶段，将记录 DEBUG 日志并安全跳过；
     * 任务执行异常会被受控隔离并记录 WARN 日志，绝对不会逆向阻断或破坏主流程
     *
     * @param task 待执行任务，不可为 null
     */
    public static void executeAfterRollback(Runnable task) {
        Objects.requireNonNull(task, "task 不能为空");
        if (isSynchronizationActive()) {
            SpringTransactionProvider.registerAfterRollback(task);
        } else {
            log.debug("当前处于非事务上下文或未激活事务同步器，跳过 executeAfterRollback 补偿回调执行");
        }
    }

    /**
     * 在事务成功提交后异步执行指定任务（调度 Spring 受管线程池）
     * 自动捕获当前调用线程的四维上下文快照（MDC 链路、User 身份、Tenant 租户、IgnoreTenant 标志位）并在异步线程还原与自清理；
     * 若当前处于非事务上下文或未激活事务同步器，则平滑降级为就地向受管线程池提交异步调度；
     * 任务执行异常会被受控隔离并记录 WARN 日志，绝对不会逆向阻断或破坏主流程
     *
     * @param task 待执行任务，不可为 null
     */
    public static void executeAfterCommitAsync(Runnable task) {
        executeAfterCommitAsync(task, null);
    }

    /**
     * 在事务成功提交后异步执行指定任务（允许指定特定 Executor 执行器）
     * 自动捕获当前调用线程的四维上下文快照（MDC 链路、User 身份、Tenant 租户、IgnoreTenant 标志位）并在异步线程还原与自清理；
     * 若当前处于非事务上下文或未激活事务同步器，则平滑降级为就地向指定线程池提交异步调度；
     * 任务执行异常会被受控隔离并记录 WARN 日志，绝对不会逆向阻断或破坏主流程
     *
     * @param task     待执行任务，不可为 null
     * @param executor 自定义任务执行器，若为 null 则自动选用 Spring 受管 taskExecutor 或保底 ForkJoinPool
     */
    public static void executeAfterCommitAsync(Runnable task, Executor executor) {
        Objects.requireNonNull(task, "task 不能为空");
        // 1. 在当前调用线程立刻抓取四维上下文快照并包装任务
        Runnable decoratedTask = AsyncContext.wrap(task);
        Runnable asyncAction = () -> {
            Executor targetExecutor = (executor != null) ? executor : obtainDefaultExecutor();
            try {
                targetExecutor.execute(() -> safeRun(decoratedTask, "afterCommitAsync"));
            } catch (Throwable ex) {
                log.warn("事务提交后异步调度任务提交至线程池失败，已受控隔离: {}", ex.getMessage(), ex);
            }
        };

        // 2. 根据事务激活状态决定延迟调度或就地异步调度
        if (isSynchronizationActive()) {
            SpringTransactionProvider.registerAfterCommit(asyncAction);
        } else {
            log.debug("当前处于非事务上下文或未激活事务同步器，executeAfterCommitAsync 就地异步调度");
            asyncAction.run();
        }
    }

    /**
     * 在事务成功提交后通过 SpringUtil 发布领域事件（支持 POJO 载荷事件）
     * 若当前处于非事务上下文或未激活事务同步器，则平滑降级为就地立即发布事件；
     * 事件发布异常会被受控隔离并记录 WARN 日志，绝对不会逆向阻断或破坏主流程
     *
     * @param event 待发布的领域事件对象，不可为 null
     */
    public static void publishAfterCommit(Object event) {
        Objects.requireNonNull(event, "event 不能为空");
        executeAfterCommit(() -> SpringUtil.publishEvent(event));
    }

    /**
     * 安全执行目标回调任务，受控捕获并记录所有异常，杜绝任何次生异常逆向溢出
     *
     * @param task       待执行任务
     * @param actionName 回调动作语义标识（用于日志诊断）
     */
    private static void safeRun(Runnable task, String actionName) {
        try {
            task.run();
        } catch (Throwable ex) {
            log.warn("事务生命周期回调 [{}] 执行失败，已受控隔离杜绝影响主流程: {}", actionName, ex.getMessage(), ex);
        }
    }

    /**
     * 获取系统默认受管异步线程池
     * 优先从 Spring 容器获取 taskExecutor 或通用 Executor，未命中或异常时安全回退至 ForkJoinPool.commonPool()
     *
     * @return 可用的 Executor 实例
     */
    private static Executor obtainDefaultExecutor() {
        try {
            if (SpringUtil.isInitialized()) {
                ApplicationContext context = SpringUtil.getApplicationContext();
                if (context.containsBean("taskExecutor")) {
                    return context.getBean("taskExecutor", Executor.class);
                }
                return context.getBean(Executor.class);
            }
        } catch (Throwable t) {
            log.debug("未从 Spring 容器获取到受管 Executor，回退至 ForkJoinPool: {}", t.getMessage());
        }
        return ForkJoinPool.commonPool();
    }

    /**
     * Spring 事务同步基础设施提供者（静态内部类延迟加载，实现缺依赖环境下的物理类隔离）
     */
    private static final class SpringTransactionProvider {

        static boolean isActualTransactionActive() {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

        static boolean isSynchronizationActive() {
            return TransactionSynchronizationManager.isSynchronizationActive();
        }

        static void registerAfterCommit(Runnable task) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeRun(task, "afterCommit");
                }
            });
        }

        static void registerAfterRollback(Runnable task) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        safeRun(task, "afterRollback");
                    }
                }
            });
        }
    }
}
