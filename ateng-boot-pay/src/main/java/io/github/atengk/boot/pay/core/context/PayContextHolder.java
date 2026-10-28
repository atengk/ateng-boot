package io.github.atengk.boot.pay.core.context;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.pay.enums.PayChannel;

import java.util.Deque;
import java.util.LinkedList;
import java.util.function.Supplier;

/**
 * 支付商户、应用与渠道上下文持有者
 * <p>
 * 维护当前线程绑定的商户标识（merchantId）、应用标识（appId）与默认支付渠道（channel），
 * 提供受控编程式闭包（runWithMerchant、runWithChannel）、线程级显式覆盖以及与 Core 模块 {@link TenantContextHolder} 的无感动态级联回退能力。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class PayContextHolder {

    /**
     * 当前线程商户 ID 栈（采用 LinkedList 支持 null 元素并支持嵌套 runWithMerchant 安全还原）
     */
    private static final ThreadLocal<Deque<String>> MERCHANT_ID_STACK = ThreadLocal.withInitial(LinkedList::new);

    /**
     * 当前线程应用 ID 栈（如指定特定微信小程序或支付宝 AppId）
     */
    private static final ThreadLocal<Deque<String>> APP_ID_STACK = ThreadLocal.withInitial(LinkedList::new);

    /**
     * 当前线程支付渠道栈
     */
    private static final ThreadLocal<Deque<PayChannel>> CHANNEL_STACK = ThreadLocal.withInitial(LinkedList::new);

    private PayContextHolder() {
    }

    /**
     * 设置当前线程商户标识
     *
     * @param merchantId 商户号或命名商户配置键
     */
    public static void setMerchantId(String merchantId) {
        Deque<String> stack = MERCHANT_ID_STACK.get();
        if (stack.isEmpty()) {
            stack.push(merchantId);
        } else {
            stack.pop();
            stack.push(merchantId);
        }
    }

    /**
     * 获取当前生效的商户标识
     * <p>
     * 级联解析策略：
     * <ol>
     *     <li>优先获取当前线程通过 {@link #setMerchantId(String)} 显式设置的商户标识；</li>
     *     <li>若当前线程未设置，自动回退级联获取 Core 模块的租户上下文 {@link TenantContextHolder#getTenantId()}；</li>
     *     <li>若均未获取到，返回 {@code null}。</li>
     * </ol>
     * </p>
     *
     * @return 当前商户标识或租户标识，若无则返回 null
     */
    public static String getMerchantId() {
        String direct = getDirectMerchantId();
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        return TenantContextHolder.getTenantId();
    }

    /**
     * 获取当前线程显式绑定的商户标识（不执行任何租户回退）
     *
     * @return 当前线程显式商户标识，若无则返回 null
     */
    public static String getDirectMerchantId() {
        Deque<String> stack = MERCHANT_ID_STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * 设置当前线程应用标识 (AppId)
     *
     * @param appId 应用标识
     */
    public static void setAppId(String appId) {
        Deque<String> stack = APP_ID_STACK.get();
        if (stack.isEmpty()) {
            stack.push(appId);
        } else {
            stack.pop();
            stack.push(appId);
        }
    }

    /**
     * 获取当前生效的应用标识 (AppId)
     * <p>
     * 若未显式设置应用标识，则默认尝试使用商户标识进行兜底。
     * </p>
     *
     * @return 应用标识，若无则返回 null
     */
    public static String getAppId() {
        String direct = getDirectAppId();
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        return getMerchantId();
    }

    /**
     * 获取当前线程显式绑定的应用标识 (AppId)（不执行任何兜底）
     *
     * @return 应用标识，若无则返回 null
     */
    public static String getDirectAppId() {
        Deque<String> stack = APP_ID_STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * 在指定商户上下文中执行闭包任务（支持嵌套且保证执行完成后现场还原）
     *
     * @param merchantId 临时绑定的商户标识
     * @param task       待执行任务
     */
    public static void runWithMerchant(String merchantId, Runnable task) {
        MERCHANT_ID_STACK.get().push(merchantId);
        try {
            task.run();
        } finally {
            popMerchant();
        }
    }

    /**
     * 在指定商户上下文中执行闭包计算并获取返回值（支持嵌套且保证执行完成后现场还原）
     *
     * @param merchantId 临时绑定的商户标识
     * @param supplier   待执行计算
     * @param <T>        返回值类型
     * @return 计算结果
     */
    public static <T> T runWithMerchant(String merchantId, Supplier<T> supplier) {
        MERCHANT_ID_STACK.get().push(merchantId);
        try {
            return supplier.get();
        } finally {
            popMerchant();
        }
    }

    /**
     * 设置当前线程支付渠道
     *
     * @param channel 支付渠道枚举
     */
    public static void setChannel(PayChannel channel) {
        Deque<PayChannel> stack = CHANNEL_STACK.get();
        if (stack.isEmpty()) {
            stack.push(channel);
        } else {
            stack.pop();
            stack.push(channel);
        }
    }

    /**
     * 获取当前生效的支付渠道
     *
     * @return 当前支付渠道枚举，未设置时返回 null
     */
    public static PayChannel getChannel() {
        Deque<PayChannel> stack = CHANNEL_STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * 在指定支付渠道上下文中执行闭包任务（支持嵌套且保证执行完成后现场还原）
     *
     * @param channel 临时绑定的支付渠道
     * @param task    待执行任务
     */
    public static void runWithChannel(PayChannel channel, Runnable task) {
        CHANNEL_STACK.get().push(channel);
        try {
            task.run();
        } finally {
            popChannel();
        }
    }

    /**
     * 在指定支付渠道上下文中执行闭包计算并获取返回值（支持嵌套且保证执行完成后现场还原）
     *
     * @param channel  临时绑定的支付渠道
     * @param supplier 待执行计算
     * @param <T>      返回值类型
     * @return 计算结果
     */
    public static <T> T runWithChannel(PayChannel channel, Supplier<T> supplier) {
        CHANNEL_STACK.get().push(channel);
        try {
            return supplier.get();
        } finally {
            popChannel();
        }
    }

    /**
     * 清理当前线程的商户、应用与渠道上下文
     */
    public static void clear() {
        MERCHANT_ID_STACK.remove();
        APP_ID_STACK.remove();
        CHANNEL_STACK.remove();
    }

    private static void popMerchant() {
        Deque<String> stack = MERCHANT_ID_STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            MERCHANT_ID_STACK.remove();
        }
    }

    private static void popChannel() {
        Deque<PayChannel> stack = CHANNEL_STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            CHANNEL_STACK.remove();
        }
    }
}
