package io.github.atengk.boot.social.http;

import lombok.extern.slf4j.Slf4j;
import org.dromara.anduin.http.HttpClientAdapter;
import org.dromara.anduin.http.UnifiedHttpClient;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;

/**
 * Dromara Anduin HTTP 适配器注入桥接器
 * <p>
 * 通过标准受控反射向 Anduin {@link UnifiedHttpClient} 注入自定义 {@link HttpClientAdapter} 实现，
 * 避免因包名侵入第三方命名空间导致的 Split-Package 缺陷与工程包契约破坏。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
public final class AnduinHttpBridge {

    private static final Field HTTP_CLIENT_ADAPTER_FIELD;

    static {
        Field field = ReflectionUtils.findField(UnifiedHttpClient.class, "httpClientAdapter");
        if (field != null) {
            ReflectionUtils.makeAccessible(field);
        } else {
            log.warn("Field 'httpClientAdapter' not found on UnifiedHttpClient");
        }
        HTTP_CLIENT_ADAPTER_FIELD = field;
    }

    private AnduinHttpBridge() {
    }

    /**
     * 设置自定义 HTTP 请求适配器
     *
     * @param adapter HTTP 请求适配器实现
     */
    public static void setAdapter(HttpClientAdapter adapter) {
        if (HTTP_CLIENT_ADAPTER_FIELD != null) {
            ReflectionUtils.setField(HTTP_CLIENT_ADAPTER_FIELD, null, adapter);
            log.debug("Successfully injected HttpClientAdapter into UnifiedHttpClient");
        } else {
            log.error("Cannot set HttpClientAdapter: httpClientAdapter field is null");
        }
    }

    /**
     * 获取当前注册的 HTTP 请求适配器
     *
     * @return 当前注册的适配器，若未设置则为 null
     */
    public static HttpClientAdapter getAdapter() {
        if (HTTP_CLIENT_ADAPTER_FIELD != null) {
            return (HttpClientAdapter) ReflectionUtils.getField(HTTP_CLIENT_ADAPTER_FIELD, null);
        }
        return null;
    }

    /**
     * 重置适配器
     */
    public static void reset() {
        setAdapter(null);
    }
}
