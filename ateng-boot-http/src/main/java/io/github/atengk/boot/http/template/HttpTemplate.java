package io.github.atengk.boot.http.template;

import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

/**
 * HTTP 通信统一命令式操作门面具体类
 * 依托 Spring 6 原生 RestClient 与受管物理连接池，对外提供简洁对称的 HTTP 操作方法
 *
 * @author Ateng
 * @since 2026-10-21
 */
public class HttpTemplate {

    private final RestClient restClient;

    /**
     * 基于已有 RestClient 实例构建 HttpTemplate
     *
     * @param restClient RestClient 实例，不可为 null
     */
    public HttpTemplate(RestClient restClient) {
        Assert.notNull(restClient, "restClient 实例不可为 null");
        this.restClient = restClient;
    }

    /**
     * 基于 RestClient.Builder 构建 HttpTemplate
     *
     * @param restClientBuilder RestClient 构建器，不可为 null
     */
    public HttpTemplate(RestClient.Builder restClientBuilder) {
        Assert.notNull(restClientBuilder, "restClientBuilder 实例不可为 null");
        this.restClient = restClientBuilder.build();
    }

    /**
     * 发起 HTTP GET 请求并反序列化为指定类类型
     *
     * @param url          请求 URL 绝对路径
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T get(String url, Class<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        return this.restClient.get()
                .uri(url)
                .retrieve()
                .body(responseType);
    }

    /**
     * 发起 HTTP POST 请求并发送 JSON 请求体
     *
     * @param url          请求 URL 绝对路径
     * @param body         请求载荷对象
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T post(String url, Object body, Class<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        RestClient.RequestBodySpec spec = this.restClient.post().uri(url);
        if (body != null) {
            spec.body(body);
        }
        return spec.retrieve().body(responseType);
    }

    /**
     * 发起 HTTP PUT 请求并发送 JSON 请求体
     *
     * @param url          请求 URL 绝对路径
     * @param body         请求载荷对象
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T put(String url, Object body, Class<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        RestClient.RequestBodySpec spec = this.restClient.put().uri(url);
        if (body != null) {
            spec.body(body);
        }
        return spec.retrieve().body(responseType);
    }

    /**
     * 发起 HTTP DELETE 请求
     *
     * @param url          请求 URL 绝对路径
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T delete(String url, Class<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        return this.restClient.delete()
                .uri(url)
                .retrieve()
                .body(responseType);
    }

    /**
     * 获取底层持有的 Spring 6 原生 RestClient 实例（供高级定制调用）
     *
     * @return RestClient 原生实例
     */
    public RestClient getClient() {
        return this.restClient;
    }
}
