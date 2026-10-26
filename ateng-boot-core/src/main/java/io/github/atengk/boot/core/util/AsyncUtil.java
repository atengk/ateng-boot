package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.ContextCopyingTaskDecorator;
import io.github.atengk.boot.core.model.ThreadPoolStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;

/**
 * 平台受管异步任务并发执行与治理统一静态门面
 * 统一调度受管平台线程池与虚拟线程，自动透传四维全链路上下文快照（MDC 链路标识、User 身份标识、Tenant 租户隔离标识及扩展维度），
 * 并提供线程池运行时全景指标探测（{@link ThreadPoolStatus}）与动态在线弹性调优能力
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Slf4j
public final class AsyncUtil {

    private static final String DEFAULT_TASK_EXECUTOR_BEAN = "taskExecutor";
    private static final String APPLICATION_TASK_EXECUTOR_BEAN = "applicationTaskExecutor";
    private static final String TASK_EXECUTOR_SUFFIX = "TaskExecutor";

    private static volatile ThreadPoolTaskExecutor fallbackExecutor;
    private static volatile Thread fallbackShutdownHook;

    private AsyncUtil() {
    }

    /**
     * 在默认受管异步线程池中执行无返回值的 Runnable 任务
     * 自动捕获当前线程四维上下文快照，在工作线程中还原并在任务完成后严格清理现场
     *
     * @param task 待执行的 Runnable 任务
     * @return 异步执行结果 CompletableFuture
     * @throws IllegalArgumentException 当 task 为 null 时抛出
     */
    public static CompletableFuture<Void> run(Runnable task) {
        return run(null, task);
    }

    /**
     * 在指定名称的受管异步线程池中执行无返回值的 Runnable 任务
     *
     * @param poolName 目标线程池 Bean 名称或业务别名（为 null 或空白时使用默认主线程池）
     * @param task     待执行的 Runnable 任务
     * @return 异步执行结果 CompletableFuture
     * @throws IllegalArgumentException 当 task 为 null 或未找到目标线程池时抛出
     */
    public static CompletableFuture<Void> run(String poolName, Runnable task) {
        Objects.requireNonNull(task, "task 不能为空");
        Executor executor = getExecutor(poolName);
        Runnable wrappedTask = AsyncContext.wrap(task);
        return CompletableFuture.runAsync(wrappedTask, executor);
    }

    /**
     * 在默认受管异步线程池中执行有返回值的 Supplier 任务
     * 自动捕获当前线程四维上下文快照，在工作线程中还原并在任务完成后严格清理现场
     *
     * @param task 待执行的 Supplier 任务
     * @param <T>  返回值泛型
     * @return 异步执行结果 CompletableFuture
     * @throws IllegalArgumentException 当 task 为 null 时抛出
     */
    public static <T> CompletableFuture<T> supply(Supplier<T> task) {
        return supply(null, task);
    }

    /**
     * 在指定名称的受管异步线程池中执行有返回值的 Supplier 任务
     *
     * @param poolName 目标线程池 Bean 名称或业务别名（为 null 或空白时使用默认主线程池）
     * @param task     待执行的 Supplier 任务
     * @param <T>      返回值泛型
     * @return 异步执行结果 CompletableFuture
     * @throws IllegalArgumentException 当 task 为 null 或未找到目标线程池时抛出
     */
    public static <T> CompletableFuture<T> supply(String poolName, Supplier<T> task) {
        Objects.requireNonNull(task, "task 不能为空");
        Executor executor = getExecutor(poolName);
        Supplier<T> wrappedSupplier = AsyncContext.wrapSupplier(task);
        return CompletableFuture.supplyAsync(wrappedSupplier, executor);
    }

    /**
     * 等待多个异步任务全部执行完成
     *
     * @param cfs 待等待的 CompletableFuture 数组
     * @return 聚合后的 CompletableFuture
     */
    public static CompletableFuture<Void> allOf(CompletableFuture<?>... cfs) {
        if (cfs == null || cfs.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(cfs);
    }

    /**
     * 等待多个异步任务全部执行完成（集合重载）
     *
     * @param cfs 待等待的 CompletableFuture 集合
     * @return 聚合后的 CompletableFuture
     */
    public static CompletableFuture<Void> allOf(Collection<? extends CompletableFuture<?>> cfs) {
        if (cfs == null || cfs.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(cfs.toArray(new CompletableFuture<?>[0]));
    }

    /**
     * 获取默认受管主线程池的运行时全景指标快照
     *
     * @return 线程池运行时状态快照
     * @throws UnsupportedOperationException 若当前执行器不支持指标提取（例如虚拟线程执行器）
     */
    public static ThreadPoolStatus getStatus() {
        return getStatus(null);
    }

    /**
     * 获取指定名称受管线程池的运行时全景指标快照
     *
     * @param poolName 目标线程池 Bean 名称或业务别名（为 null 或空白时使用默认主线程池）
     * @return 线程池运行时状态快照
     * @throws UnsupportedOperationException 若当前执行器不支持指标提取（例如虚拟线程执行器）
     */
    public static ThreadPoolStatus getStatus(String poolName) {
        Executor executor = getExecutor(poolName);
        if (executor instanceof ThreadPoolTaskExecutor taskExecutor) {
            return ThreadPoolStatus.of(taskExecutor);
        }
        if (executor instanceof ThreadPoolExecutor tpe) {
            return ThreadPoolStatus.of(tpe);
        }
        throw new UnsupportedOperationException("执行器 [" + (StringUtils.hasText(poolName) ? poolName : "default")
                + "] 类型为 [" + executor.getClass().getName() + "]，不支持获取 ThreadPoolStatus 监控指标");
    }

    /**
     * 动态在线调整默认受管主线程池的核心与最大容量
     * 具备越界防卫机制，自适应根据扩容或缩容时序调整参数，彻底消除 JDK 内部非法状态异常风险
     *
     * @param corePoolSize 新的核心线程数
     * @param maxPoolSize  新的最大线程数
     * @throws IllegalArgumentException 当参数非法时抛出
     * @throws UnsupportedOperationException 若当前执行器不支持动态调参
     */
    public static void resize(int corePoolSize, int maxPoolSize) {
        resize(null, corePoolSize, maxPoolSize);
    }

    /**
     * 动态在线调整指定受管线程池的核心与最大容量
     *
     * @param poolName     目标线程池 Bean 名称或业务别名（为 null 或空白时使用默认主线程池）
     * @param corePoolSize 新的核心线程数
     * @param maxPoolSize  新的最大线程数
     * @throws IllegalArgumentException 当参数非法或未找到目标执行器时抛出
     * @throws UnsupportedOperationException 若当前执行器不支持动态调参
     */
    public static void resize(String poolName, int corePoolSize, int maxPoolSize) {
        if (corePoolSize < 0) {
            throw new IllegalArgumentException("corePoolSize 不能小于 0");
        }
        if (maxPoolSize <= 0) {
            throw new IllegalArgumentException("maxPoolSize 必须大于 0");
        }
        if (corePoolSize > maxPoolSize) {
            throw new IllegalArgumentException("corePoolSize [" + corePoolSize + "] 不能大于 maxPoolSize [" + maxPoolSize + "]");
        }

        Executor executor = getExecutor(poolName);
        if (executor instanceof ThreadPoolTaskExecutor taskExecutor) {
            resizeTaskExecutor(taskExecutor, corePoolSize, maxPoolSize);
            log.info("ateng-boot: 成功动态调整受管线程池 [{}] 容量参数: corePoolSize={}, maxPoolSize={}",
                    StringUtils.hasText(poolName) ? poolName : "default", corePoolSize, maxPoolSize);
            return;
        }
        if (executor instanceof ThreadPoolExecutor tpe) {
            resizeThreadPoolExecutor(tpe, corePoolSize, maxPoolSize);
            log.info("ateng-boot: 成功动态调整原生线程池 [{}] 容量参数: corePoolSize={}, maxPoolSize={}",
                    StringUtils.hasText(poolName) ? poolName : "default", corePoolSize, maxPoolSize);
            return;
        }
        throw new UnsupportedOperationException("执行器 [" + (StringUtils.hasText(poolName) ? poolName : "default")
                + "] 类型为 [" + executor.getClass().getName() + "]，不支持动态在线调参");
    }

    /**
     * 获取默认受管主异步任务执行器
     *
     * @return Executor 实例
     */
    public static Executor getExecutor() {
        return getExecutor(null);
    }

    /**
     * 获取指定名称的受管异步任务执行器
     *
     * @param poolName 线程池 Bean 名称或别名（为 null 或空白时获取默认主执行器）
     * @return Executor 实例
     * @throws IllegalArgumentException 当无法根据名称定位目标 Bean 时抛出
     */
    public static Executor getExecutor(String poolName) {
        if (!StringUtils.hasText(poolName)) {
            return resolveDefaultExecutor();
        }
        return resolveNamedExecutor(poolName.trim());
    }

    private static Executor resolveDefaultExecutor() {
        if (SpringUtil.isInitialized()) {
            Executor executor = findExecutorBean(DEFAULT_TASK_EXECUTOR_BEAN);
            if (executor != null) {
                return executor;
            }
            executor = findExecutorBean(APPLICATION_TASK_EXECUTOR_BEAN);
            if (executor != null) {
                return executor;
            }
            try {
                return SpringUtil.getBean(Executor.class);
            } catch (Exception ignored) {
                // 降级使用保底执行器
            }
        }
        return getOrCreateFallbackExecutor();
    }

    private static Executor resolveNamedExecutor(String poolName) {
        if (!SpringUtil.isInitialized()) {
            throw new IllegalStateException("Spring 容器尚未就绪，无法按名称查找异步线程池: " + poolName);
        }

        // 1. 尝试直接按名称获取
        Executor executor = findExecutorBean(poolName);
        if (executor != null) {
            return executor;
        }

        // 2. 尝试添加 TaskExecutor 后缀获取（如 export -> exportTaskExecutor）
        if (!poolName.endsWith(TASK_EXECUTOR_SUFFIX)) {
            executor = findExecutorBean(poolName + TASK_EXECUTOR_SUFFIX);
            if (executor != null) {
                return executor;
            }
        }

        // 3. 尝试去除 TaskExecutor 后缀查找别名（如 exportTaskExecutor -> export）
        if (poolName.endsWith(TASK_EXECUTOR_SUFFIX)) {
            String candidate = poolName.substring(0, poolName.length() - TASK_EXECUTOR_SUFFIX.length());
            executor = findExecutorBean(candidate);
            if (executor != null) {
                return executor;
            }
        }

        throw new IllegalArgumentException("未找到名称或别名为 [" + poolName + "] 的受管异步执行器 Bean");
    }

    private static Executor findExecutorBean(String beanName) {
        if (StringUtils.hasText(beanName) && SpringUtil.containsBean(beanName)) {
            Object bean = SpringUtil.getBean(beanName);
            if (bean instanceof Executor executor) {
                return executor;
            }
        }
        return null;
    }

    private static void resizeTaskExecutor(ThreadPoolTaskExecutor taskExecutor, int corePoolSize, int maxPoolSize) {
        ThreadPoolExecutor underlyingExecutor = null;
        try {
            underlyingExecutor = taskExecutor.getThreadPoolExecutor();
        } catch (IllegalStateException ignored) {
            // 未初始化时忽略
        }

        if (underlyingExecutor != null) {
            resizeThreadPoolExecutor(underlyingExecutor, corePoolSize, maxPoolSize);
        }
        taskExecutor.setCorePoolSize(corePoolSize);
        taskExecutor.setMaxPoolSize(maxPoolSize);
    }

    private static void resizeThreadPoolExecutor(ThreadPoolExecutor executor, int corePoolSize, int maxPoolSize) {
        int currentMax = executor.getMaximumPoolSize();

        // 调参顺序防护：
        // 若 newCore > currentMax，必须先调大 maxPoolSize，否则 setCorePoolSize 会抛出 IllegalArgumentException
        if (corePoolSize > currentMax) {
            executor.setMaximumPoolSize(maxPoolSize);
            executor.setCorePoolSize(corePoolSize);
        } else {
            // 若 newMax < currentCore，必须先调小 corePoolSize，否则 setMaximumPoolSize 会抛出 IllegalArgumentException
            executor.setCorePoolSize(corePoolSize);
            executor.setMaximumPoolSize(maxPoolSize);
        }
    }

    private static ThreadPoolTaskExecutor getOrCreateFallbackExecutor() {
        if (fallbackExecutor == null) {
            synchronized (AsyncUtil.class) {
                if (fallbackExecutor == null) {
                    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                    int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
                    executor.setCorePoolSize(cores);
                    executor.setMaxPoolSize(cores * 2);
                    executor.setQueueCapacity(1000);
                    executor.setThreadNamePrefix("ateng-fallback-async-");
                    executor.setTaskDecorator(new ContextCopyingTaskDecorator());
                    executor.initialize();

                    Thread hook = new Thread(executor::shutdown, "ateng-fallback-async-shutdown");
                    try {
                        Runtime.getRuntime().addShutdownHook(hook);
                        fallbackShutdownHook = hook;
                    } catch (IllegalStateException ignored) {
                        // JVM 正在关闭时忽略
                    }
                    fallbackExecutor = executor;
                }
            }
        }
        return fallbackExecutor;
    }

    /**
     * 重置脱离 Spring 容器时的保底线程池（仅供单元测试清理现场使用）
     */
    static synchronized void resetFallbackExecutor() {
        if (fallbackShutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(fallbackShutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM 正在关闭时忽略
            }
            fallbackShutdownHook = null;
        }
        if (fallbackExecutor != null) {
            fallbackExecutor.shutdown();
            fallbackExecutor = null;
        }
    }
}
