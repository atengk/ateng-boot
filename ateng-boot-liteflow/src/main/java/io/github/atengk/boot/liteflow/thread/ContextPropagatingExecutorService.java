package io.github.atengk.boot.liteflow.thread;

import io.github.atengk.boot.core.context.AsyncContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 上下文透明透传执行器服务代理
 * 拦截对 {@link ExecutorService} 的所有调度调用，自动通过 {@link AsyncContext#wrap} 挂载四维上下文快照，
 * 并在工作线程执行完成后严格清理现场，同时对受管 Spring 共享线程池实施关闭保护
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class ContextPropagatingExecutorService implements ExecutorService {

    private static final Logger log = LoggerFactory.getLogger(ContextPropagatingExecutorService.class);

    private final ExecutorService delegate;
    private final boolean shutdownProtected;
    private final boolean propagateContext;

    /**
     * 构造上下文穿透执行器代理（默认开启上下文透传）
     *
     * @param delegate          底层真实线程池执行器
     * @param shutdownProtected 是否对受管共享线程池实施关闭保护
     */
    public ContextPropagatingExecutorService(ExecutorService delegate, boolean shutdownProtected) {
        this(delegate, shutdownProtected, true);
    }

    /**
     * 构造上下文穿透执行器代理
     *
     * @param delegate          底层真实线程池执行器
     * @param shutdownProtected 是否对受管共享线程池实施关闭保护
     * @param propagateContext  是否将四维上下文透传至子线程
     */
    public ContextPropagatingExecutorService(ExecutorService delegate, boolean shutdownProtected, boolean propagateContext) {
        this.delegate = Objects.requireNonNull(delegate, "delegate ExecutorService must not be null");
        this.shutdownProtected = shutdownProtected;
        this.propagateContext = propagateContext;
    }

    /**
     * 获取底层被代理的真实线程池
     *
     * @return 底层 ExecutorService
     */
    public ExecutorService getDelegate() {
        return delegate;
    }

    /**
     * 是否处于受保护关闭模式
     *
     * @return 若受保护则返回 true
     */
    public boolean isShutdownProtected() {
        return shutdownProtected;
    }

    /**
     * 是否启用四维上下文透传
     *
     * @return 若启用返回 true
     */
    public boolean isPropagateContext() {
        return propagateContext;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(wrapCommand(command));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(wrapTask(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(wrapCommand(task), result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(wrapCommand(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        Objects.requireNonNull(tasks, "tasks must not be null");
        List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            wrapped.add(wrapTask(task));
        }
        return delegate.invokeAll(wrapped);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        Objects.requireNonNull(tasks, "tasks must not be null");
        List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            wrapped.add(wrapTask(task));
        }
        return delegate.invokeAll(wrapped, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        Objects.requireNonNull(tasks, "tasks must not be null");
        List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            wrapped.add(wrapTask(task));
        }
        return delegate.invokeAny(wrapped);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(tasks, "tasks must not be null");
        List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            wrapped.add(wrapTask(task));
        }
        return delegate.invokeAny(wrapped, timeout, unit);
    }

    private Runnable wrapCommand(Runnable command) {
        if (command == null) {
            return null;
        }
        String requestId = FlowThreadTracker.getCurrentRequestId();
        if (requestId == null) {
            return propagateContext ? AsyncContext.wrap(command) : command;
        }
        Runnable tracked = () -> {
            Thread current = Thread.currentThread();
            FlowThreadTracker.register(requestId, current);
            try {
                command.run();
            } finally {
                FlowThreadTracker.unregister(requestId, current);
            }
        };
        return propagateContext ? AsyncContext.wrap(tracked) : tracked;
    }

    private <T> Callable<T> wrapTask(Callable<T> task) {
        if (task == null) {
            return null;
        }
        String requestId = FlowThreadTracker.getCurrentRequestId();
        if (requestId == null) {
            return propagateContext ? AsyncContext.wrap(task) : task;
        }
        Callable<T> tracked = () -> {
            Thread current = Thread.currentThread();
            FlowThreadTracker.register(requestId, current);
            try {
                return task.call();
            } finally {
                FlowThreadTracker.unregister(requestId, current);
            }
        };
        return propagateContext ? AsyncContext.wrap(tracked) : tracked;
    }

    @Override
    public void shutdown() {
        if (shutdownProtected) {
            log.debug("ateng-boot-liteflow: 拦截对 Spring 受管共享线程池的 shutdown 调用，确保容器生命周期一致性");
            return;
        }
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        if (shutdownProtected) {
            log.debug("ateng-boot-liteflow: 拦截对 Spring 受管共享线程池的 shutdownNow 调用，确保容器生命周期一致性");
            return Collections.emptyList();
        }
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
