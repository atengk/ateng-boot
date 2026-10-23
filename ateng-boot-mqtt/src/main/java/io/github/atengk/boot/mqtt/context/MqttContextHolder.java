package io.github.atengk.boot.mqtt.context;

import java.util.function.Supplier;

/**
 * 线程级 MQTT 命名客户端路由上下文持有者
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class MqttContextHolder {

    private static final ThreadLocal<String> CURRENT_CLIENT = new ThreadLocal<>();

    private MqttContextHolder() {
    }

    /**
     * 设置当前线程绑定的 MQTT 客户端名称
     *
     * @param clientName 客户端逻辑名称
     */
    public static void setClientName(String clientName) {
        CURRENT_CLIENT.set(clientName);
    }

    /**
     * 获取当前线程绑定的 MQTT 客户端名称
     *
     * @return 客户端逻辑名称，若未绑定返回 null
     */
    public static String getClientName() {
        return CURRENT_CLIENT.get();
    }

    /**
     * 清理当前线程绑定的 MQTT 客户端名称
     */
    public static void clear() {
        CURRENT_CLIENT.remove();
    }

    /**
     * 在指定的客户端上下文中执行操作，执行完毕后自动还原上下文
     *
     * @param clientName 目标客户端名称
     * @param supplier   操作逻辑
     * @param <T>        返回值类型
     * @return 执行结果
     */
    public static <T> T runWith(String clientName, Supplier<T> supplier) {
        String previous = CURRENT_CLIENT.get();
        try {
            CURRENT_CLIENT.set(clientName);
            return supplier.get();
        } finally {
            if (previous != null) {
                CURRENT_CLIENT.set(previous);
            } else {
                CURRENT_CLIENT.remove();
            }
        }
    }

    /**
     * 在指定的客户端上下文中执行无返回值的操作，执行完毕后自动还原上下文
     *
     * @param clientName 目标客户端名称
     * @param runnable   操作逻辑
     */
    public static void runWith(String clientName, Runnable runnable) {
        runWith(clientName, () -> {
            runnable.run();
            return null;
        });
    }
}
