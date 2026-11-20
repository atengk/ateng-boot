package io.github.atengk.boot.search.spi;

/**
 * 搜索引擎数据面操作拦截器扩展点 SPI
 * <p>
 * 支持通过 Spring {@code @Order} 或 {@link org.springframework.core.Ordered} 指定执行优先级，
 * 用于实现统一业务审计、观测注入、安全治理或耗时统计。
 *
 * @author Ateng
 * @since 2026-11-20
 */
public interface SearchInterceptor {

    /**
     * 在底层引擎操作执行前触发（按正序依次执行）
     *
     * @param context 拦截上下文
     */
    default void beforeExecute(SearchInvocationContext context) {
    }

    /**
     * 在底层引擎操作成功返回后触发（按逆序依次执行）
     *
     * @param context 拦截上下文
     * @param result  操作返回值（可能为 null）
     */
    default void afterExecute(SearchInvocationContext context, Object result) {
    }

    /**
     * 在底层引擎操作抛出异常时触发（按逆序依次执行）
     *
     * @param context 拦截上下文
     * @param cause   捕获的异常对象
     */
    default void onError(SearchInvocationContext context, Throwable cause) {
    }
}
