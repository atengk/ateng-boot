package io.github.atengk.boot.http.interceptor;

import io.github.atengk.boot.http.properties.HttpClientItemProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * HTTP 拦截器链路内部辅助工具
 * 负责从出站请求头中读取并严格剥离框架私有控制头，以及依据 URL 最长前缀匹配客户端配置
 *
 * @author Ateng
 * @since 2026-10-21
 */
public final class HttpInterceptorUtil {

    private HttpInterceptorUtil() {
    }

    /**
     * 从请求头中提取并移除指定键名的布尔值控制头
     *
     * @param headers    请求头集合
     * @param headerName 目标控制头键名
     * @return 解析后的布尔值；若不存在或为空则返回 null
     */
    public static Boolean extractBooleanHeader(HttpHeaders headers, String headerName) {
        String val = extractStringHeader(headers, headerName);
        return StringUtils.hasText(val) ? Boolean.parseBoolean(val) : null;
    }

    /**
     * 从请求头中提取并移除指定键名的字符串控制头
     *
     * @param headers    请求头集合
     * @param headerName 目标控制头键名
     * @return 经 trim 处理后的非空字符串；若不存在或为空则返回 null
     */
    public static String extractStringHeader(HttpHeaders headers, String headerName) {
        if (headers == null || !headers.containsKey(headerName)) {
            return null;
        }
        List<String> values = headers.remove(headerName);
        if (CollectionUtils.isEmpty(values)) {
            return null;
        }
        String first = values.get(0);
        return StringUtils.hasText(first) ? first.trim() : null;
    }

    /**
     * 依据 URL 最长前缀匹配从多客户端配置中查找最佳匹配的客户端条目
     *
     * @param uri     目标请求 URI
     * @param clients 命名客户端配置字典
     * @return 最佳匹配的 Client 配置条目，未匹配到返回 null
     */
    public static Map.Entry<String, HttpClientItemProperties> matchClientByUrl(URI uri, Map<String, HttpClientItemProperties> clients) {
        if (uri == null || clients == null || clients.isEmpty()) {
            return null;
        }
        Map.Entry<String, HttpClientItemProperties> matched = null;
        int maxMatchLength = -1;
        String uriStr = uri.toString();

        for (Map.Entry<String, HttpClientItemProperties> entry : clients.entrySet()) {
            String baseUrl = entry.getValue() != null ? entry.getValue().getBaseUrl() : null;
            if (StringUtils.hasText(baseUrl) && uriStr.startsWith(baseUrl)) {
                if (baseUrl.length() > maxMatchLength) {
                    maxMatchLength = baseUrl.length();
                    matched = entry;
                }
            }
        }
        return matched;
    }
}
