package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.util.JsonUtil;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * 基于 Spring 6 RestClient 的云厂商 HTTP 短信驱动抽象基类
 * 统一收敛网络客户端构建与 JSON 响应解析通用逻辑
 *
 * @author Ateng
 * @since 2026-11-03
 */
public abstract class AbstractHttpSmsProvider implements SmsProvider {

    protected final RestClient.Builder restClientBuilder;

    protected AbstractHttpSmsProvider(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder != null ? restClientBuilder : RestClient.builder();
    }

    /**
     * 根据渠道指定的超时时间构建独立的 RestClient 实例
     *
     * @param timeout 超时时长
     * @return RestClient 实例
     */
    protected RestClient buildRestClient(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        if (timeout != null) {
            factory.setConnectTimeout(timeout);
            factory.setReadTimeout(timeout);
        }
        return restClientBuilder.clone()
                .requestFactory(factory)
                .build();
    }

    /**
     * 安全解析 JSON 字符串为 Map 对象，解析异常时保底返回空 Map
     *
     * @param json JSON 字符串
     * @return 键值对 Map
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> parseJsonSafely(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return JsonUtil.parseObject(json, Map.class);
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }
}
