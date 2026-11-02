package io.github.atengk.boot.social.context;

/**
 * 当前线程社交应用调用上下文持有者
 * <p>
 * 用于在发起多平台 OAuth2 授权请求时，临时绑定当前执行的 appKey，
 * 使底层的统一网络适配器能够自动路由至该应用专属的网络代理（如海外 GitHub 走 HTTP 代理，国内微信保持直连）
 * 与专有超时配置。采用 ThreadLocal 严格实现跨线程隔离与用后确定性清理。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public final class CurrentSocialAppContextHolder {

    private static final ThreadLocal<String> CURRENT_APP_KEY = new ThreadLocal<>();

    private CurrentSocialAppContextHolder() {
    }

    /**
     * 获取当前线程绑定的社交应用 appKey
     *
     * @return 当前 appKey；未绑定时返回 null
     */
    public static String getAppKey() {
        return CURRENT_APP_KEY.get();
    }

    /**
     * 设置当前线程绑定的社交应用 appKey
     *
     * @param appKey 社交应用唯一标识
     */
    public static void setAppKey(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            CURRENT_APP_KEY.remove();
        } else {
            CURRENT_APP_KEY.set(appKey);
        }
    }

    /**
     * 清理当前线程绑定的社交应用上下文
     */
    public static void clear() {
        CURRENT_APP_KEY.remove();
    }
}
