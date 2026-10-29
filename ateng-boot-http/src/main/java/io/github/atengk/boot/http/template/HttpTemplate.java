package io.github.atengk.boot.http.template;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.http.constant.HttpConstants;
import io.github.atengk.boot.http.error.HttpErrorHandler;
import io.github.atengk.boot.http.error.HttpRemoteException;
import io.github.atengk.boot.http.options.RequestOptions;
import io.github.atengk.boot.http.options.RequestOptionsApplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * HTTP 通信统一命令式操作门面具体类
 * 依托 Spring 6 原生 RestClient 与受管物理连接池，对外提供简洁对称的 HTTP 操作方法
 *
 * @author Ateng
 * @since 2026-10-21
 */
public class HttpTemplate {

    private static final int BUFFER_SIZE = 8192; // 8KB 零堆缓冲直刷

    private final RestClient restClient;
    private final RestClient streamingRestClient;
    private final HttpErrorHandler httpErrorHandler;
    private final Executor executor;

    /**
     * 基于已有 RestClient 实例构建 HttpTemplate
     *
     * @param restClient RestClient 实例，不可为 null
     */
    public HttpTemplate(RestClient restClient) {
        this(restClient, new HttpErrorHandler(), null);
    }

    /**
     * 基于 RestClient、HttpErrorHandler 与受管异步执行器构建 HttpTemplate
     *
     * @param restClient       RestClient 实例，不可为 null
     * @param httpErrorHandler HTTP 统一异常响应处理器
     * @param executor         受管异步任务执行器（为 null 时默认采用 ForkJoinPool.commonPool()）
     */
    public HttpTemplate(RestClient restClient, HttpErrorHandler httpErrorHandler, Executor executor) {
        this(restClient, restClient, httpErrorHandler, executor);
    }

    /**
     * 基于 RestClient.Builder 构建 HttpTemplate
     * 自动通过 clone() 清除拦截器链构建流式专职 streamingRestClient，旁路内存全量缓冲
     *
     * @param restClientBuilder RestClient 构建器，不可为 null
     */
    public HttpTemplate(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, new HttpErrorHandler(), null);
    }

    /**
     * 基于 RestClient.Builder、HttpErrorHandler 与受管异步执行器构建 HttpTemplate
     *
     * @param restClientBuilder RestClient 构建器，不可为 null
     * @param httpErrorHandler  HTTP 统一异常响应处理器
     * @param executor          受管异步任务执行器（为 null 时默认采用 ForkJoinPool.commonPool()）
     */
    public HttpTemplate(RestClient.Builder restClientBuilder, HttpErrorHandler httpErrorHandler, Executor executor) {
        Assert.notNull(restClientBuilder, "restClientBuilder 实例不可为 null");
        this.restClient = restClientBuilder.build();
        this.streamingRestClient = restClientBuilder.clone()
                .requestInterceptors(List::clear)
                .build();
        this.httpErrorHandler = httpErrorHandler != null ? httpErrorHandler : new HttpErrorHandler();
        this.executor = executor != null ? executor : ForkJoinPool.commonPool();
    }

    /**
     * 基于普通 RestClient、直通流式 RestClient、HttpErrorHandler 与受管异步执行器构建 HttpTemplate
     *
     * @param restClient          基础 RestClient 实例，不可为 null
     * @param streamingRestClient 直通流式 RestClient 实例（旁路拦截器内存缓冲），若为 null 则退化使用 restClient
     * @param httpErrorHandler    HTTP 统一异常响应处理器
     * @param executor            受管异步任务执行器（为 null 时默认采用 ForkJoinPool.commonPool()）
     */
    public HttpTemplate(RestClient restClient, RestClient streamingRestClient, HttpErrorHandler httpErrorHandler, Executor executor) {
        Assert.notNull(restClient, "restClient 实例不可为 null");
        this.restClient = restClient;
        this.streamingRestClient = streamingRestClient != null ? streamingRestClient : restClient;
        this.httpErrorHandler = httpErrorHandler != null ? httpErrorHandler : new HttpErrorHandler();
        this.executor = executor != null ? executor : ForkJoinPool.commonPool();
    }

    // ==========================================
    // GET 操作
    // ==========================================

    /**
     * 发起 HTTP GET 请求并反序列化为指定类类型
     *
     * @param url          请求 URL 绝对路径
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T get(String url, Class<T> responseType) {
        return get(url, null, responseType);
    }

    /**
     * 发起 HTTP GET 请求并依据复杂泛型类型反序列化响应体
     *
     * @param url          请求 URL 绝对路径
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T get(String url, ParameterizedTypeReference<T> responseType) {
        return get(url, null, responseType);
    }

    /**
     * 发起携带动态 RequestOptions 配置的 HTTP GET 请求并反序列化为指定类类型
     *
     * @param url          请求 URL 绝对路径
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T get(String url, RequestOptions options, Class<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestHeadersSpec<?> spec = this.restClient.get().uri(uri);
        RequestOptionsApplier.apply(spec, options);
        return spec.retrieve().body(responseType);
    }

    /**
     * 发起携带动态 RequestOptions 配置的 HTTP GET 请求并反序列化为复杂泛型类型
     *
     * @param url          请求 URL 绝对路径
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T get(String url, RequestOptions options, ParameterizedTypeReference<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestHeadersSpec<?> spec = this.restClient.get().uri(uri);
        RequestOptionsApplier.apply(spec, options);
        return spec.retrieve().body(responseType);
    }

    // ==========================================
    // GET OPTIONAL 空安全操作
    // ==========================================

    /**
     * 发起 HTTP GET 请求并安全返回 Optional 包装对象
     * 仅当远端响应 404 Not Found 时安全返回 Optional.empty()，其余非 2xx 抛出 HttpRemoteException
     *
     * @param url          请求 URL 绝对路径
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return Optional 包装的实体对象
     */
    public <T> Optional<T> getOptional(String url, Class<T> responseType) {
        return getOptional(url, null, responseType);
    }

    /**
     * 发起 HTTP GET 请求并安全返回带有深层泛型的 Optional 包装对象
     * 仅当远端响应 404 Not Found 时安全返回 Optional.empty()，其余非 2xx 抛出 HttpRemoteException
     *
     * @param url          请求 URL 绝对路径
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return Optional 包装的实体对象
     */
    public <T> Optional<T> getOptional(String url, ParameterizedTypeReference<T> responseType) {
        return getOptional(url, null, responseType);
    }

    /**
     * 发起携带动态 RequestOptions 配置的 HTTP GET 请求并安全返回 Optional 包装对象
     * 仅当远端响应 404 Not Found 时安全返回 Optional.empty()，其余非 2xx 抛出 HttpRemoteException
     *
     * @param url          请求 URL 绝对路径
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return Optional 包装的实体对象
     */
    public <T> Optional<T> getOptional(String url, RequestOptions options, Class<T> responseType) {
        return executeOptional(() -> get(url, options, responseType));
    }

    /**
     * 发起携带动态 RequestOptions 配置的 HTTP GET 请求并安全返回复杂泛型 Optional 包装对象
     * 仅当远端响应 404 Not Found 时安全返回 Optional.empty()，其余非 2xx 抛出 HttpRemoteException
     *
     * @param url          请求 URL 绝对路径
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return Optional 包装的实体对象
     */
    public <T> Optional<T> getOptional(String url, RequestOptions options, ParameterizedTypeReference<T> responseType) {
        return executeOptional(() -> get(url, options, responseType));
    }

    private <T> Optional<T> executeOptional(java.util.function.Supplier<T> supplier) {
        try {
            return Optional.ofNullable(supplier.get());
        } catch (HttpRemoteException ex) {
            if (ex.getHttpStatusCode() == 404) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    // ==========================================
    // POST 操作
    // ==========================================

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
        return post(url, body, null, responseType);
    }

    /**
     * 发起 HTTP POST 请求并依据复杂泛型类型反序列化响应体
     *
     * @param url          请求 URL 绝对路径
     * @param body         请求载荷对象
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T post(String url, Object body, ParameterizedTypeReference<T> responseType) {
        return post(url, body, null, responseType);
    }

    /**
     * 发起携带动态 RequestOptions 配置的 HTTP POST 请求
     *
     * @param url          请求 URL 绝对路径
     * @param body         请求载荷对象
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T post(String url, Object body, RequestOptions options, Class<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestBodySpec spec = this.restClient.post().uri(uri);
        RequestOptionsApplier.apply(spec, options);
        if (body != null) {
            spec.body(body);
        }
        return spec.retrieve().body(responseType);
    }

    /**
     * 发起携带动态 RequestOptions 配置的 HTTP POST 请求并反序列化为复杂泛型
     *
     * @param url          请求 URL 绝对路径
     * @param body         请求载荷对象
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T post(String url, Object body, RequestOptions options, ParameterizedTypeReference<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestBodySpec spec = this.restClient.post().uri(uri);
        RequestOptionsApplier.apply(spec, options);
        if (body != null) {
            spec.body(body);
        }
        return spec.retrieve().body(responseType);
    }

    // ==========================================
    // POST FORM 表单提交 (application/x-www-form-urlencoded)
    // ==========================================

    /**
     * 发起 application/x-www-form-urlencoded 标准表单 POST 请求
     *
     * @param url          请求 URL 绝对路径
     * @param formData     表单键值对集合
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T postForm(String url, MultiValueMap<String, ?> formData, Class<T> responseType) {
        return postForm(url, formData, null, responseType);
    }

    /**
     * 发起 application/x-www-form-urlencoded 标准表单 POST 请求并反序列化为复杂泛型
     *
     * @param url          请求 URL 绝对路径
     * @param formData     表单键值对集合
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T postForm(String url, MultiValueMap<String, ?> formData, ParameterizedTypeReference<T> responseType) {
        return postForm(url, formData, null, responseType);
    }

    /**
     * 携带动态 RequestOptions 发起 application/x-www-form-urlencoded 标准表单 POST 请求
     *
     * @param url          请求 URL 绝对路径
     * @param formData     表单键值对集合
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T postForm(String url, MultiValueMap<String, ?> formData, RequestOptions options, Class<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestBodySpec spec = this.restClient.post().uri(uri);
        RequestOptionsApplier.apply(spec, options);
        spec.contentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (formData != null) {
            spec.body(formData);
        }
        return spec.retrieve().body(responseType);
    }

    /**
     * 携带动态 RequestOptions 发起 application/x-www-form-urlencoded 标准表单 POST 请求并反序列化为复杂泛型
     *
     * @param url          请求 URL 绝对路径
     * @param formData     表单键值对集合
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T postForm(String url, MultiValueMap<String, ?> formData, RequestOptions options, ParameterizedTypeReference<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestBodySpec spec = this.restClient.post().uri(uri);
        RequestOptionsApplier.apply(spec, options);
        spec.contentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (formData != null) {
            spec.body(formData);
        }
        return spec.retrieve().body(responseType);
    }

    /**
     * 基于普通 Map 字典发起 application/x-www-form-urlencoded 标准表单 POST 请求
     *
     * @param url          请求 URL 绝对路径
     * @param formData     普通 Map 字典（自动转译为 MultiValueMap）
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T postForm(String url, Map<String, ?> formData, Class<T> responseType) {
        return postForm(url, toMultiValueMap(formData), null, responseType);
    }

    /**
     * 基于普通 Map 字典发起 application/x-www-form-urlencoded 标准表单 POST 请求并反序列化为复杂泛型
     *
     * @param url          请求 URL 绝对路径
     * @param formData     普通 Map 字典（自动转译为 MultiValueMap）
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T postForm(String url, Map<String, ?> formData, ParameterizedTypeReference<T> responseType) {
        return postForm(url, toMultiValueMap(formData), null, responseType);
    }

    /**
     * 携带动态 RequestOptions 并基于普通 Map 字典发起标准表单 POST 请求
     *
     * @param url          请求 URL 绝对路径
     * @param formData     普通 Map 字典（自动转译为 MultiValueMap）
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T postForm(String url, Map<String, ?> formData, RequestOptions options, Class<T> responseType) {
        return postForm(url, toMultiValueMap(formData), options, responseType);
    }

    /**
     * 携带动态 RequestOptions 并基于普通 Map 字典发起标准表单 POST 请求并反序列化为复杂泛型
     *
     * @param url          请求 URL 绝对路径
     * @param formData     普通 Map 字典（自动转译为 MultiValueMap）
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T postForm(String url, Map<String, ?> formData, RequestOptions options, ParameterizedTypeReference<T> responseType) {
        return postForm(url, toMultiValueMap(formData), options, responseType);
    }

    private static MultiValueMap<String, Object> toMultiValueMap(Map<String, ?> map) {
        MultiValueMap<String, Object> multiValueMap = new LinkedMultiValueMap<>();
        if (map != null) {
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    if (v instanceof Iterable<?> iterable) {
                        for (Object item : iterable) {
                            if (item != null) {
                                multiValueMap.add(k, item);
                            }
                        }
                    } else if (v instanceof Object[] array) {
                        for (Object item : array) {
                            if (item != null) {
                                multiValueMap.add(k, item);
                            }
                        }
                    } else if (v.getClass().isArray()) {
                        int length = java.lang.reflect.Array.getLength(v);
                        for (int i = 0; i < length; i++) {
                            Object item = java.lang.reflect.Array.get(v, i);
                            if (item != null) {
                                multiValueMap.add(k, item);
                            }
                        }
                    } else {
                        multiValueMap.add(k, v);
                    }
                }
            });
        }
        return multiValueMap;
    }

    // ==========================================
    // PUT 操作
    // ==========================================

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
        return put(url, body, null, responseType);
    }

    /**
     * 发起 HTTP PUT 请求并依据复杂泛型类型反序列化响应体
     *
     * @param url          请求 URL 绝对路径
     * @param body         请求载荷对象
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T put(String url, Object body, ParameterizedTypeReference<T> responseType) {
        return put(url, body, null, responseType);
    }

    /**
     * 发起携带动态 RequestOptions 配置的 HTTP PUT 请求
     *
     * @param url          请求 URL 绝对路径
     * @param body         请求载荷对象
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T put(String url, Object body, RequestOptions options, Class<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestBodySpec spec = this.restClient.put().uri(uri);
        RequestOptionsApplier.apply(spec, options);
        if (body != null) {
            spec.body(body);
        }
        return spec.retrieve().body(responseType);
    }

    /**
     * 发起携带动态 RequestOptions 配置的 HTTP PUT 请求并反序列化为复杂泛型
     *
     * @param url          请求 URL 绝对路径
     * @param body         请求载荷对象
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T put(String url, Object body, RequestOptions options, ParameterizedTypeReference<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestBodySpec spec = this.restClient.put().uri(uri);
        RequestOptionsApplier.apply(spec, options);
        if (body != null) {
            spec.body(body);
        }
        return spec.retrieve().body(responseType);
    }

    // ==========================================
    // PATCH 操作 (RFC 5789 局部更新)
    // ==========================================

    /**
     * 发起 HTTP PATCH 请求并发送 JSON 请求体
     *
     * @param url          请求 URL 绝对路径
     * @param body         请求载荷对象
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T patch(String url, Object body, Class<T> responseType) {
        return patch(url, body, null, responseType);
    }

    /**
     * 发起 HTTP PATCH 请求并依据复杂泛型类型反序列化响应体
     *
     * @param url          请求 URL 绝对路径
     * @param body         请求载荷对象
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T patch(String url, Object body, ParameterizedTypeReference<T> responseType) {
        return patch(url, body, null, responseType);
    }

    /**
     * 发起携带动态 RequestOptions 配置的 HTTP PATCH 请求
     *
     * @param url          请求 URL 绝对路径
     * @param body         请求载荷对象
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T patch(String url, Object body, RequestOptions options, Class<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestBodySpec spec = this.restClient.patch().uri(uri);
        RequestOptionsApplier.apply(spec, options);
        if (body != null) {
            spec.body(body);
        }
        return spec.retrieve().body(responseType);
    }

    /**
     * 发起携带动态 RequestOptions 配置的 HTTP PATCH 请求并反序列化为复杂泛型
     *
     * @param url          请求 URL 绝对路径
     * @param body         请求载荷对象
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T patch(String url, Object body, RequestOptions options, ParameterizedTypeReference<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestBodySpec spec = this.restClient.patch().uri(uri);
        RequestOptionsApplier.apply(spec, options);
        if (body != null) {
            spec.body(body);
        }
        return spec.retrieve().body(responseType);
    }

    // ==========================================
    // DELETE 操作
    // ==========================================

    /**
     * 发起 HTTP DELETE 请求
     *
     * @param url          请求 URL 绝对路径
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T delete(String url, Class<T> responseType) {
        return delete(url, null, responseType);
    }

    /**
     * 发起 HTTP DELETE 请求并依据复杂泛型类型反序列化响应体
     *
     * @param url          请求 URL 绝对路径
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T delete(String url, ParameterizedTypeReference<T> responseType) {
        return delete(url, null, responseType);
    }

    /**
     * 发起携带动态 RequestOptions 配置的 HTTP DELETE 请求
     *
     * @param url          请求 URL 绝对路径
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T delete(String url, RequestOptions options, Class<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestHeadersSpec<?> spec = this.restClient.delete().uri(uri);
        RequestOptionsApplier.apply(spec, options);
        return spec.retrieve().body(responseType);
    }

    /**
     * 发起携带动态 RequestOptions 配置的 HTTP DELETE 请求并反序列化为复杂泛型
     *
     * @param url          请求 URL 绝对路径
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T delete(String url, RequestOptions options, ParameterizedTypeReference<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestHeadersSpec<?> spec = this.restClient.delete().uri(uri);
        RequestOptionsApplier.apply(spec, options);
        return spec.retrieve().body(responseType);
    }

    // ==========================================
    // HEAD 操作 (元数据提取)
    // ==========================================

    /**
     * 发起 HTTP HEAD 请求，仅获取响应头元数据而不下载响应体
     *
     * @param url 请求 URL 绝对路径
     * @return 远端响应头对象
     */
    public HttpHeaders head(String url) {
        return head(url, null);
    }

    /**
     * 携带动态 RequestOptions 发起 HTTP HEAD 请求
     *
     * @param url     请求 URL 绝对路径
     * @param options 单次请求配置载荷（可为 null）
     * @return 远端响应头对象
     */
    public HttpHeaders head(String url, RequestOptions options) {
        Assert.hasText(url, "请求 URL 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestHeadersSpec<?> spec = this.restClient.head().uri(uri);
        RequestOptionsApplier.apply(spec, options);
        return spec.retrieve().toBodilessEntity().getHeaders();
    }

    // ==========================================
    // OPTIONS 操作 (端点能力探测)
    // ==========================================

    /**
     * 发起 HTTP OPTIONS 请求，获取目标端点支持的完整响应头（含 Allow 与 CORS 头）
     *
     * @param url 请求 URL 绝对路径
     * @return 远端响应头对象
     */
    public HttpHeaders options(String url) {
        return options(url, null);
    }

    /**
     * 携带动态 RequestOptions 发起 HTTP OPTIONS 请求
     *
     * @param url     请求 URL 绝对路径
     * @param options 单次请求配置载荷（可为 null）
     * @return 远端响应头对象
     */
    public HttpHeaders options(String url, RequestOptions options) {
        Assert.hasText(url, "请求 URL 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestHeadersSpec<?> spec = this.restClient.options().uri(uri);
        RequestOptionsApplier.apply(spec, options);
        return spec.retrieve().toBodilessEntity().getHeaders();
    }

    /**
     * 发起 HTTP OPTIONS 请求并提取允许的 HTTP 动词集合 (Allow Header)
     *
     * @param url 请求 URL 绝对路径
     * @return 允许的 HttpMethod 集合（若未返回 Allow 头则返回空集合）
     */
    public Set<HttpMethod> optionsForAllow(String url) {
        return optionsForAllow(url, null);
    }

    /**
     * 携带动态 RequestOptions 发起 HTTP OPTIONS 请求并提取允许的 HTTP 动词集合
     *
     * @param url     请求 URL 绝对路径
     * @param options 单次请求配置载荷（可为 null）
     * @return 允许的 HttpMethod 集合（若未返回 Allow 头则返回空集合）
     */
    public Set<HttpMethod> optionsForAllow(String url, RequestOptions options) {
        HttpHeaders headers = options(url, options);
        return headers != null ? headers.getAllow() : Set.of();
    }

    // ==========================================
    // 实体包装与元数据穿透操作 (ResponseEntity)
    // ==========================================

    /**
     * 发起 HTTP GET 请求并返回完整的 ResponseEntity（含 HTTP 状态码与响应头）
     *
     * @param url          请求 URL 绝对路径
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 包含响应头与状态码的 ResponseEntity 对象
     */
    public <T> ResponseEntity<T> getEntity(String url, Class<T> responseType) {
        return getEntity(url, null, responseType);
    }

    /**
     * 发起 HTTP GET 请求并依据复杂泛型返回完整的 ResponseEntity
     *
     * @param url          请求 URL 绝对路径
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 包含响应头与状态码的 ResponseEntity 对象
     */
    public <T> ResponseEntity<T> getEntity(String url, ParameterizedTypeReference<T> responseType) {
        return getEntity(url, null, responseType);
    }

    /**
     * 携带动态 RequestOptions 发起 HTTP GET 请求并返回完整的 ResponseEntity
     *
     * @param url          请求 URL 绝对路径
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 包含响应头与状态码的 ResponseEntity 对象
     */
    public <T> ResponseEntity<T> getEntity(String url, RequestOptions options, Class<T> responseType) {
        return exchange(url, HttpMethod.GET, null, options, responseType);
    }

    /**
     * 携带动态 RequestOptions 发起 HTTP GET 请求并依据复杂泛型返回完整的 ResponseEntity
     *
     * @param url          请求 URL 绝对路径
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 包含响应头与状态码的 ResponseEntity 对象
     */
    public <T> ResponseEntity<T> getEntity(String url, RequestOptions options, ParameterizedTypeReference<T> responseType) {
        return exchange(url, HttpMethod.GET, null, options, responseType);
    }

    /**
     * 通用 HTTP 动词交换方法，执行任意请求并返回完整的 ResponseEntity
     *
     * @param url          请求 URL 绝对路径
     * @param method       HTTP 动词
     * @param body         请求载荷对象（可为 null）
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 包含响应头与状态码的 ResponseEntity 对象
     */
    public <T> ResponseEntity<T> exchange(String url, HttpMethod method, Object body, Class<T> responseType) {
        return exchange(url, method, body, null, responseType);
    }

    /**
     * 通用 HTTP 动词交换方法，执行任意请求并依据复杂泛型返回完整的 ResponseEntity
     *
     * @param url          请求 URL 绝对路径
     * @param method       HTTP 动词
     * @param body         请求载荷对象（可为 null）
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 包含响应头与状态码的 ResponseEntity 对象
     */
    public <T> ResponseEntity<T> exchange(String url, HttpMethod method, Object body, ParameterizedTypeReference<T> responseType) {
        return exchange(url, method, body, null, responseType);
    }

    /**
     * 携带动态 RequestOptions 的通用 HTTP 动词交换方法
     *
     * @param url          请求 URL 绝对路径
     * @param method       HTTP 动词
     * @param body         请求载荷对象（可为 null）
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体 Class 类型
     * @param <T>          泛型返回类型
     * @return 包含响应头与状态码的 ResponseEntity 对象
     */
    public <T> ResponseEntity<T> exchange(String url, HttpMethod method, Object body, RequestOptions options, Class<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(method, "HttpMethod 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestBodySpec spec = this.restClient.method(method).uri(uri);
        RequestOptionsApplier.apply(spec, options);
        if (body != null) {
            spec.body(body);
        }
        return spec.retrieve().toEntity(responseType);
    }

    /**
     * 携带动态 RequestOptions 的通用 HTTP 动词交换方法（复杂泛型反序列化）
     *
     * @param url          请求 URL 绝对路径
     * @param method       HTTP 动词
     * @param body         请求载荷对象（可为 null）
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应体泛型类型引用
     * @param <T>          泛型返回类型
     * @return 包含响应头与状态码的 ResponseEntity 对象
     */
    public <T> ResponseEntity<T> exchange(String url, HttpMethod method, Object body, RequestOptions options, ParameterizedTypeReference<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(method, "HttpMethod 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestBodySpec spec = this.restClient.method(method).uri(uri);
        RequestOptionsApplier.apply(spec, options);
        if (body != null) {
            spec.body(body);
        }
        return spec.retrieve().toEntity(responseType);
    }

    // ==========================================
    // 大模型与事件流流式消费 (SSE / NDJSON)
    // ==========================================

    /**
     * 流式消费远程事件流或大模型文本行（如 SSE text/event-stream 或 application/x-ndjson）
     * 依托 BufferedReader.readLine() 零内存堆积实时按行推流，严格遵循 RFC 8895 保留空行作为 SSE 事件切片边界
     *
     * @param url          请求 URL 绝对路径
     * @param lineConsumer 单行消费回调（空行传入 "" 作为事件分隔边界，回调不可为 null）
     */
    public void consumeStream(String url, Consumer<String> lineConsumer) {
        consumeStream(url, HttpMethod.GET, null, null, lineConsumer);
    }

    /**
     * 携带动态 RequestOptions 流式消费远程事件流或大模型文本行
     *
     * @param url          请求 URL 绝对路径
     * @param options      单次请求配置载荷（可为 null）
     * @param lineConsumer 单行消费回调（空行传入 "" 作为事件分隔边界，回调不可为 null）
     */
    public void consumeStream(String url, RequestOptions options, Consumer<String> lineConsumer) {
        consumeStream(url, HttpMethod.GET, null, options, lineConsumer);
    }

    /**
     * 携带 HTTP 动词与请求载荷流式消费远程事件流或大模型文本行（支持 AI 大模型 POST /chat/completions 等场景）
     *
     * @param url          请求 URL 绝对路径
     * @param method       HTTP 请求动词（如 GET 或 POST）
     * @param body         请求载荷对象（可为 null）
     * @param lineConsumer 单行消费回调（空行传入 "" 作为事件分隔边界，回调不可为 null）
     */
    public void consumeStream(String url, HttpMethod method, Object body, Consumer<String> lineConsumer) {
        consumeStream(url, method, body, null, lineConsumer);
    }

    /**
     * 携带 HTTP 动词、请求载荷及动态 RequestOptions 流式消费远程事件流或大模型文本行
     * 依托 BufferedReader.readLine() 零内存堆积实时按行推流，严格遵循 RFC 8895 保留空行作为 SSE 事件切片边界
     *
     * @param url          请求 URL 绝对路径
     * @param method       HTTP 请求动词（如 GET 或 POST）
     * @param body         请求载荷对象（可为 null）
     * @param options      单次请求配置载荷（可为 null）
     * @param lineConsumer 单行消费回调（空行传入 "" 作为事件分隔边界，回调不可为 null）
     */
    public void consumeStream(String url, HttpMethod method, Object body, RequestOptions options, Consumer<String> lineConsumer) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(method, "HttpMethod 不能为空");
        Assert.notNull(lineConsumer, "lineConsumer 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestBodySpec spec = this.restClient.method(method).uri(uri);
        spec.header(HttpConstants.HEADER_STREAM_TRANSFER, "true");
        spec.accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_NDJSON, MediaType.ALL);
        RequestOptionsApplier.apply(spec, options);
        if (body != null) {
            spec.body(body);
        }
        spec.exchange((request, response) -> {
            if (response.getStatusCode().isError()) {
                this.httpErrorHandler.handleError(uri, method, response);
            }
            Charset charset = StandardCharsets.UTF_8;
            MediaType contentType = response.getHeaders().getContentType();
            if (contentType != null && contentType.getCharset() != null) {
                charset = contentType.getCharset();
            }
            try (InputStream in = response.getBody();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineConsumer.accept(line);
                }
            }
            return null;
        });
    }

    // ==========================================
    // 大流传输（零缓冲下载与上传）
    // ==========================================

    /**
     * 流式下载远程大文件并直刷至指定的 OutputStream（8KB 定长缓冲，杜绝 JVM 堆膨胀）
     *
     * @param url          请求 URL 绝对路径
     * @param outputStream 目标输出流（由调用方负责生命周期关闭）
     */
    public void download(String url, OutputStream outputStream) {
        download(url, null, outputStream);
    }

    /**
     * 携带动态 RequestOptions 流式下载远程大文件并直刷至指定的 OutputStream
     *
     * @param url          请求 URL 绝对路径
     * @param options      单次请求配置载荷（可为 null）
     * @param outputStream 目标输出流（由调用方负责生命周期关闭）
     */
    public void download(String url, RequestOptions options, OutputStream outputStream) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(outputStream, "outputStream 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestHeadersSpec<?> spec = this.restClient.get().uri(uri);
        spec.header(HttpConstants.HEADER_STREAM_TRANSFER, "true");
        RequestOptionsApplier.apply(spec, options);
        spec.exchange((request, response) -> {
            if (response.getStatusCode().isError()) {
                this.httpErrorHandler.handleError(uri, HttpMethod.GET, response);
            }
            try (InputStream in = response.getBody()) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.flush();
            }
            return null;
        });
    }

    /**
     * 流式下载远程文件并直接保存至指定的本地文件路径
     *
     * @param url        请求 URL 绝对路径
     * @param targetFile 本地目标文件路径
     */
    public void download(String url, Path targetFile) {
        download(url, null, targetFile);
    }

    /**
     * 携带动态 RequestOptions 流式下载远程文件并直接保存至指定的本地文件路径
     * 若下载过程中发生异常，自动执行补偿清理以删除脏文件或 0 字节文件
     *
     * @param url        请求 URL 绝对路径
     * @param options    单次请求配置载荷（可为 null）
     * @param targetFile 本地目标文件路径
     */
    public void download(String url, RequestOptions options, Path targetFile) {
        Assert.notNull(targetFile, "targetFile 不能为空");
        try {
            try (OutputStream out = Files.newOutputStream(targetFile)) {
                download(url, options, out);
            }
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(targetFile);
            } catch (IOException ignored) {
                // 忽略次生清理异常
            }
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new BusinessException("写入目标本地文件失败: " + targetFile, t);
        }
    }

    /**
     * 单资源流式 Multipart 文件上传
     *
     * @param url          请求 URL 绝对路径
     * @param partName     分片字段名（如 "file"）
     * @param resource     待上传资源实体
     * @param responseType 响应类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T upload(String url, String partName, Resource resource, Class<T> responseType) {
        return upload(url, partName, resource, null, responseType);
    }

    /**
     * 携带 RequestOptions 的单资源流式 Multipart 文件上传
     *
     * @param url          请求 URL 绝对路径
     * @param partName     分片字段名（如 "file"）
     * @param resource     待上传资源实体
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T upload(String url, String partName, Resource resource, RequestOptions options, Class<T> responseType) {
        Assert.hasText(partName, "partName 不能为空");
        Assert.notNull(resource, "resource 不能为空");
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(partName, resource);
        return upload(url, parts, options, responseType);
    }

    /**
     * 单资源流式 Multipart 文件上传（复杂泛型反序列化）
     *
     * @param url          请求 URL 绝对路径
     * @param partName     分片字段名（如 "file"）
     * @param resource     待上传资源实体
     * @param responseType 复杂泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T upload(String url, String partName, Resource resource, ParameterizedTypeReference<T> responseType) {
        return upload(url, partName, resource, null, responseType);
    }

    /**
     * 携带 RequestOptions 的单资源流式 Multipart 文件上传（复杂泛型反序列化）
     *
     * @param url          请求 URL 绝对路径
     * @param partName     分片字段名（如 "file"）
     * @param resource     待上传资源实体
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 复杂泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T upload(String url, String partName, Resource resource, RequestOptions options, ParameterizedTypeReference<T> responseType) {
        Assert.hasText(partName, "partName 不能为空");
        Assert.notNull(resource, "resource 不能为空");
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(partName, resource);
        return upload(url, parts, options, responseType);
    }

    /**
     * 多分片流式 Multipart 文件及表单上传（免传 options）
     *
     * @param url          请求 URL 绝对路径
     * @param parts        分片及表单数据字典
     * @param responseType 响应类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T upload(String url, MultiValueMap<String, Object> parts, Class<T> responseType) {
        return upload(url, parts, null, responseType);
    }

    /**
     * 多分片流式 Multipart 上传并反序列化为复杂泛型（免传 options）
     *
     * @param url          请求 URL 绝对路径
     * @param parts        分片及表单数据字典
     * @param responseType 复杂泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T upload(String url, MultiValueMap<String, Object> parts, ParameterizedTypeReference<T> responseType) {
        return upload(url, parts, null, responseType);
    }

    /**
     * 携带 RequestOptions 的多分片流式 Multipart 文件及表单上传
     *
     * @param url          请求 URL 绝对路径
     * @param parts        分片及表单数据字典
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 响应类型
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T upload(String url, MultiValueMap<String, Object> parts, RequestOptions options, Class<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(parts, "parts 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestBodySpec spec = this.streamingRestClient.post().uri(uri);
        spec.header(HttpConstants.HEADER_STREAM_TRANSFER, "true");
        RequestOptionsApplier.apply(spec, options);
        spec.contentType(MediaType.MULTIPART_FORM_DATA);
        spec.body(parts);
        return spec.retrieve().body(responseType);
    }

    /**
     * 携带 RequestOptions 的多分片流式 Multipart 上传并反序列化为复杂泛型
     *
     * @param url          请求 URL 绝对路径
     * @param parts        分片及表单数据字典
     * @param options      单次请求配置载荷（可为 null）
     * @param responseType 复杂泛型类型引用
     * @param <T>          泛型返回类型
     * @return 反序列化后的实体对象
     */
    public <T> T upload(String url, MultiValueMap<String, Object> parts, RequestOptions options, ParameterizedTypeReference<T> responseType) {
        Assert.hasText(url, "请求 URL 不能为空");
        Assert.notNull(parts, "parts 不能为空");
        Assert.notNull(responseType, "responseType 不能为空");
        URI uri = RequestOptionsApplier.resolveUri(url, options);
        RestClient.RequestBodySpec spec = this.streamingRestClient.post().uri(uri);
        spec.header(HttpConstants.HEADER_STREAM_TRANSFER, "true");
        RequestOptionsApplier.apply(spec, options);
        spec.contentType(MediaType.MULTIPART_FORM_DATA);
        spec.body(parts);
        return spec.retrieve().body(responseType);
    }

    // ==========================================
    // 受管异步执行器（四维上下文传播）
    // ==========================================

    /**
     * 在受管异步线程池中执行 HTTP 请求，自动透传四维上下文快照（MDC/User/Tenant/IgnoreTenant）
     *
     * @param task 异步请求执行体
     * @param <T>  返回实体泛型
     * @return CompletableFuture 异步结果凭证
     */
    public <T> CompletableFuture<T> executeAsync(Supplier<T> task) {
        Assert.notNull(task, "异步执行 task 不能为空");
        Supplier<T> wrappedTask = AsyncContext.wrapSupplier(task);
        return CompletableFuture.supplyAsync(wrappedTask, this.executor);
    }

    /**
     * 获取底层持有的 Spring 6 原生 RestClient 实例（供高级定制调用）
     *
     * @return RestClient 原生实例
     */
    public RestClient getClient() {
        return this.restClient;
    }

    /**
     * 获取底层持有的用于直通大流传输的 RestClient 实例（已旁路拦截器内存缓冲）
     *
     * @return 直通流式 RestClient 实例
     */
    public RestClient getStreamingClient() {
        return this.streamingRestClient;
    }
}
