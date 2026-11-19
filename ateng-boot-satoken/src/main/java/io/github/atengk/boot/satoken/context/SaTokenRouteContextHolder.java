package io.github.atengk.boot.satoken.context;

/**
 * 当前 Web 请求命中的多账号路由体系 ({@code loginType}) 线程上下文持有者
 * <p>
 * 由 {@link io.github.atengk.boot.satoken.interceptor.AtengSaTokenInterceptor} 在 {@code preHandle}
 * （含白名单分支）写入当前请求命中的 {@code loginType}，供 Sa-Token 鉴权注解处理器与
 * {@link io.github.atengk.boot.satoken.aspect.SaCheckDataOwnerAspect} 无感读取，
 * 并在 {@code afterCompletion} 及异常退出路径中确定性清理，防止容器线程池复用污染。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
public final class SaTokenRouteContextHolder {

    private static final ThreadLocal<String> ROUTE_LOGIN_TYPE = new ThreadLocal<>();

    private SaTokenRouteContextHolder() {
    }

    /**
     * 设置当前请求线程命中的账号体系标识
     *
     * @param loginType 账号体系标识（为空或空白时等价于清理）
     */
    public static void set(String loginType) {
        if (loginType == null || loginType.isBlank()) {
            ROUTE_LOGIN_TYPE.remove();
            return;
        }
        ROUTE_LOGIN_TYPE.set(loginType.trim());
    }

    /**
     * 获取当前请求线程命中的账号体系标识
     *
     * @return 账号体系标识，未命中时返回 null
     */
    public static String get() {
        return ROUTE_LOGIN_TYPE.get();
    }

    /**
     * 清理当前请求线程的路由账号体系上下文
     */
    public static void clear() {
        ROUTE_LOGIN_TYPE.remove();
    }
}
