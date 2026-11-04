package io.github.atengk.boot.ai.streaming;

import io.github.atengk.boot.core.context.AsyncContext;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * 支持流式上下文与全链路快照透传的受管线程池包装器
 * 拦截 OkHttp Dispatcher 异步任务分发，跨线程透传 streamId 与 AsyncContext，确保异步 Interceptor 准确识别上下文
 *
 * @author Ateng
 * @since 2026-11-04
 */
public class StreamPropagatingExecutorService implements ExecutorService {

    private final ExecutorService delegate;

    public StreamPropagatingExecutorService(ExecutorService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "底层 ExecutorService 不能为空");
    }

    public ExecutorService getDelegate() {
        return delegate;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(wrap(command));
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
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

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(wrap(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(wrap(task), result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(wrap(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(wrapCollection(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(wrapCollection(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(wrapCollection(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(wrapCollection(tasks), timeout, unit);
    }

    private Runnable wrap(Runnable command) {
        if (command == null) {
            return null;
        }
        String streamId = StreamContextHolder.getStreamId();
        AsyncContext.ContextSnapshot snapshot = AsyncContext.ContextSnapshot.capture();
        return () -> {
            String prevStreamId = StreamContextHolder.getStreamId();
            AsyncContext.ContextSnapshot prevSnapshot = AsyncContext.ContextSnapshot.capturePrevious();
            try {
                if (streamId != null) {
                    StreamContextHolder.setStreamId(streamId);
                }
                snapshot.apply();
                command.run();
            } finally {
                prevSnapshot.restore();
                if (streamId != null) {
                    if (prevStreamId != null) {
                        StreamContextHolder.setStreamId(prevStreamId);
                    } else {
                        StreamContextHolder.clear();
                    }
                }
            }
        };
    }

    private <T> Callable<T> wrap(Callable<T> task) {
        if (task == null) {
            return null;
        }
        String streamId = StreamContextHolder.getStreamId();
        AsyncContext.ContextSnapshot snapshot = AsyncContext.ContextSnapshot.capture();
        return () -> {
            String prevStreamId = StreamContextHolder.getStreamId();
            AsyncContext.ContextSnapshot prevSnapshot = AsyncContext.ContextSnapshot.capturePrevious();
            try {
                if (streamId != null) {
                    StreamContextHolder.setStreamId(streamId);
                }
                snapshot.apply();
                return task.call();
            } finally {
                prevSnapshot.restore();
                if (streamId != null) {
                    if (prevStreamId != null) {
                        StreamContextHolder.setStreamId(prevStreamId);
                    } else {
                        StreamContextHolder.clear();
                    }
                }
            }
        };
    }

    private <T> Collection<? extends Callable<T>> wrapCollection(Collection<? extends Callable<T>> tasks) {
        if (tasks == null) {
            return null;
        }
        return tasks.stream().map(this::wrap).toList();
    }
}
