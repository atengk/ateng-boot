package org.dromara.anduin.http;

/**
 * UnifiedHttpClient 访问适配器
 * 用于向 Dromara Anduin 注入自定义 HttpClientAdapter 实现
 *
 * @author Ateng
 * @since 2026-11-03
 */
public final class UnifiedHttpClientAccessor {

    private UnifiedHttpClientAccessor() {
    }

    /**
     * 设置自定义 HTTP 请求适配器
     *
     * @param adapter HTTP 请求适配器实现
     */
    public static void setAdapter(HttpClientAdapter adapter) {
        UnifiedHttpClient.httpClientAdapter = adapter;
    }

    /**
     * 获取当前注册的 HTTP 请求适配器
     *
     * @return 当前注册的适配器，若未设置则为 null
     */
    public static HttpClientAdapter getAdapter() {
        return UnifiedHttpClient.httpClientAdapter;
    }

    /**
     * 重置适配器
     */
    public static void reset() {
        UnifiedHttpClient.httpClientAdapter = null;
    }
}
