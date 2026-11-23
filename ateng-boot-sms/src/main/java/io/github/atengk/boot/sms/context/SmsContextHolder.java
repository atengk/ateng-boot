package io.github.atengk.boot.sms.context;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.AsyncContextSnapshotProvider;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 短信渠道线程级动态切源上下文持有者
 * 依托 ThreadLocal 显式指定当前线程使用的短信渠道名称，提供受控编程式闭包与跨线程受管异步透传能力
 *
 * @author Ateng
 * @since 2026-11-02
 */
public final class SmsContextHolder {

    private static final ThreadLocal<String> CURRENT_CHANNEL = new ThreadLocal<>();

    static {
        // 挂载至 Core 异步全链路上下文快照提供者列表，实现跨受管线程池透明透传与现场自恢复
        AsyncContext.registerSnapshotProvider(new SmsAsyncContextSnapshotProvider());
    }

    private SmsContextHolder() {
    }

    /**
     * 设置当前线程发信渠道名称
     *
     * @param channel 渠道名称
     */
    public static void setChannel(String channel) {
        if (channel != null && !channel.isBlank()) {
            CURRENT_CHANNEL.set(channel.trim());
        } else {
            clear();
        }
    }

    /**
     * 获取当前线程绑定的发信渠道名称
     *
     * @return 渠道名称，若未显式指定则返回 null
     */
    public static String getChannel() {
        return CURRENT_CHANNEL.get();
    }

    /**
     * 清理当前线程绑定的渠道现场，杜绝线程池污染
     */
    public static void clear() {
        CURRENT_CHANNEL.remove();
    }

    /**
     * 在指定发信渠道上下文中执行带返回值的任务，执行完毕后严格恢复调用前线程现场
     *
     * @param channel  目标渠道名称
     * @param supplier 待执行的任务
     * @param <T>      返回值泛型
     * @return 任务执行结果
     */
    public static <T> T runWithChannel(String channel, Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier 不能为空");
        String previous = getChannel();
        try {
            setChannel(channel);
            return supplier.get();
        } finally {
            if (previous != null) {
                setChannel(previous);
            } else {
                clear();
            }
        }
    }

    /**
     * 在指定发信渠道上下文中执行无返回值的任务，执行完毕后严格恢复调用前线程现场
     *
     * @param channel  目标渠道名称
     * @param runnable 待执行的任务
     */
    public static void runWithChannel(String channel, Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable 不能为空");
        runWithChannel(channel, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 内部异步全链路上下文快照扩展提供者实现
     */
    private static class SmsAsyncContextSnapshotProvider implements AsyncContextSnapshotProvider {

        @Override
        public Object capture() {
            return getChannel();
        }

        @Override
        public void apply(Object snapshot) {
            if (snapshot instanceof String ch) {
                setChannel(ch);
            } else {
                clear();
            }
        }

        @Override
        public void restore(Object previous) {
            if (previous instanceof String ch) {
                setChannel(ch);
            } else {
                clear();
            }
        }
    }
}
