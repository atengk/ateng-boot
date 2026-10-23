package io.github.atengk.boot.notification.context;

import org.springframework.core.NamedThreadLocal;

import java.util.concurrent.Callable;

/**
 * 协同通知线程级动态切源上下文持有者
 * 支持在当前线程生命周期内隐式切换生效的通知渠道标识，执行完成后可靠清理现场防污染
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class NotificationContextHolder {

    private static final ThreadLocal<String> CURRENT_CHANNEL = new NamedThreadLocal<>("ateng-notification-channel");

    private NotificationContextHolder() {
    }

    /**
     * 设置当前线程绑定的通知渠道名称
     *
     * @param channelName 目标渠道名称；若为空白字符则视为清除
     */
    public static void setChannel(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            clear();
        } else {
            CURRENT_CHANNEL.set(channelName.trim());
        }
    }

    /**
     * 获取当前线程绑定的通知渠道名称
     *
     * @return 当前渠道名称；若未绑定则返回 null
     */
    public static String getChannel() {
        return CURRENT_CHANNEL.get();
    }

    /**
     * 清理当前线程绑定的通知渠道上下文
     */
    public static void clear() {
        CURRENT_CHANNEL.remove();
    }

    /**
     * 在指定的通知渠道上下文中执行无返回值的任务
     * 支持多层上下文嵌套并严格在 finally 中恢复上一层上下文
     *
     * @param channelName 目标渠道名称
     * @param runnable    待执行的无返回值逻辑
     */
    public static void runWithChannel(String channelName, Runnable runnable) {
        if (runnable == null) {
            return;
        }
        String previous = getChannel();
        try {
            setChannel(channelName);
            runnable.run();
        } finally {
            restorePrevious(previous);
        }
    }

    /**
     * 在指定的通知渠道上下文中执行有返回值的任务
     * 支持多层上下文嵌套并严格在 finally 中恢复上一层上下文
     *
     * @param channelName 目标渠道名称
     * @param callable    待执行的有返回值逻辑
     * @param <T>         返回值泛型
     * @return 执行结果
     * @throws Exception 业务抛出的受检异常
     */
    public static <T> T callWithChannel(String channelName, Callable<T> callable) throws Exception {
        if (callable == null) {
            return null;
        }
        String previous = getChannel();
        try {
            setChannel(channelName);
            return callable.call();
        } finally {
            restorePrevious(previous);
        }
    }

    private static void restorePrevious(String previous) {
        if (previous != null && !previous.isBlank()) {
            setChannel(previous);
        } else {
            clear();
        }
    }
}
