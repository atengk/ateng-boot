package io.github.atengk.boot.http.options;

import lombok.Getter;
import org.springframework.http.HttpHeaders;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 单次 HTTP 请求维度的动态参数配置载荷
 * 保持严格不可变语义，支持动态注入 Header、Query 参数、URI 路径变量以及单次请求超时覆盖
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Getter
public final class RequestOptions {

    private static final RequestOptions EMPTY = new RequestOptions(
            new HttpHeaders(),
            new LinkedMultiValueMap<>(),
            Map.of(),
            null,
            null,
            null
    );

    /**
     * 自定义 HTTP 请求头（只读不可变视图）
     */
    private final HttpHeaders headers;

    /**
     * 结构化 URL 查询参数（只读不可变视图）
     */
    private final MultiValueMap<String, String> queryParams;

    /**
     * URI 路径模板变量字典（只读不可变视图）
     */
    private final Map<String, Object> uriVariables;

    /**
     * 单次请求读取超时时间（覆盖全局 readTimeout 配置）
     */
    private final Duration readTimeout;

    /**
     * 单次请求四维上下文出站透传控制开关（为 null 时依客户端或全局规则自适应判断）
     */
    private final Boolean propagateContext;

    /**
     * 显式指定归属的命名客户端标识（为 null 时依据请求 URL 自动进行最长前缀匹配）
     */
    private final String clientName;

    private RequestOptions(HttpHeaders headers,
                           MultiValueMap<String, String> queryParams,
                           Map<String, Object> uriVariables,
                           Duration readTimeout,
                           Boolean propagateContext,
                           String clientName) {
        this.headers = HttpHeaders.readOnlyHttpHeaders(headers != null ? headers : new HttpHeaders());
        this.queryParams = CollectionUtils.unmodifiableMultiValueMap(
                queryParams != null ? new LinkedMultiValueMap<>(queryParams) : new LinkedMultiValueMap<>()
        );
        this.uriVariables = uriVariables != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(uriVariables))
                : Collections.emptyMap();
        this.readTimeout = readTimeout;
        this.propagateContext = propagateContext;
        this.clientName = clientName;
    }

    /**
     * 获取全局空配置载荷单例
     *
     * @return 空 RequestOptions 实例
     */
    public static RequestOptions empty() {
        return EMPTY;
    }

    /**
     * 获取 RequestOptions 建造者实例
     *
     * @return Builder 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 基于函数式 Lambda 风格快速构建 RequestOptions 实例
     *
     * @param consumer 构建逻辑回调
     * @return 构建后的 RequestOptions 实例
     */
    public static RequestOptions of(Consumer<Builder> consumer) {
        Assert.notNull(consumer, "构建回调不可为 null");
        Builder builder = builder();
        consumer.accept(builder);
        return builder.build();
    }

    /**
     * RequestOptions 建造者
     */
    public static final class Builder {

        private final HttpHeaders headers = new HttpHeaders();
        private final MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        private final Map<String, Object> uriVariables = new LinkedHashMap<>();
        private Duration readTimeout;
        private Boolean propagateContext;
        private String clientName;

        private Builder() {
        }

        /**
         * 追加单条 HTTP 请求头
         *
         * @param headerName   请求头名称
         * @param headerValues 请求头取值
         * @return 当前构建器
         */
        public Builder header(String headerName, String... headerValues) {
            Assert.hasText(headerName, "headerName 不能为空");
            if (headerValues != null) {
                for (String value : headerValues) {
                    if (value != null) {
                        this.headers.add(headerName, value);
                    }
                }
            }
            return this;
        }

        /**
         * 批量追加 HTTP 请求头
         *
         * @param headers 请求头对象
         * @return 当前构建器
         */
        public Builder headers(HttpHeaders headers) {
            if (headers != null) {
                this.headers.addAll(headers);
            }
            return this;
        }

        /**
         * 基于 Map 字典批量追加 HTTP 请求头
         *
         * @param headerMap 请求头 Map 映射
         * @return 当前构建器
         */
        public Builder headers(Map<String, ?> headerMap) {
            if (headerMap != null) {
                headerMap.forEach((k, v) -> {
                    if (k != null && v != null) {
                        this.headers.add(k, String.valueOf(v));
                    }
                });
            }
            return this;
        }

        /**
         * 追加单个 Query 查询参数
         *
         * @param name   参数键名
         * @param values 参数取值数组
         * @return 当前构建器
         */
        public Builder queryParam(String name, Object... values) {
            Assert.hasText(name, "Query 参数名称不能为空");
            if (values != null) {
                for (Object value : values) {
                    if (value != null) {
                        this.queryParams.add(name, String.valueOf(value));
                    }
                }
            }
            return this;
        }

        /**
         * 批量追加 MultiValueMap 类型的 Query 查询参数
         *
         * @param queryParams 查询参数映射
         * @return 当前构建器
         */
        public Builder queryParams(MultiValueMap<String, String> queryParams) {
            if (queryParams != null) {
                this.queryParams.addAll(queryParams);
            }
            return this;
        }

        /**
         * 批量追加普通 Map 映射的 Query 查询参数
         *
         * @param queryParamMap 查询参数 Map
         * @return 当前构建器
         */
        public Builder queryParams(Map<String, ?> queryParamMap) {
            if (queryParamMap != null) {
                queryParamMap.forEach((k, v) -> {
                    if (k != null && v != null) {
                        this.queryParams.add(k, String.valueOf(v));
                    }
                });
            }
            return this;
        }

        /**
         * 注入单个 URI 路径模板变量（如 /users/{userId} 中的 userId）
         *
         * @param name  变量名
         * @param value 变量值
         * @return 当前构建器
         */
        public Builder uriVariable(String name, Object value) {
            Assert.hasText(name, "URI 模板变量名不能为空");
            if (value != null) {
                this.uriVariables.put(name, value);
            }
            return this;
        }

        /**
         * 批量注入 URI 路径模板变量
         *
         * @param uriVariables 模板变量字典
         * @return 当前构建器
         */
        public Builder uriVariables(Map<String, ?> uriVariables) {
            if (uriVariables != null) {
                uriVariables.forEach((k, v) -> {
                    if (k != null && v != null) {
                        this.uriVariables.put(k, v);
                    }
                });
            }
            return this;
        }

        /**
         * 设置单次请求的读取超时时间（覆盖全局 readTimeout）
         *
         * @param readTimeout 读取超时时长
         * @return 当前构建器
         */
        public Builder readTimeout(Duration readTimeout) {
            if (readTimeout != null) {
                Assert.isTrue(!readTimeout.isNegative(), "readTimeout 不能为负数");
            }
            this.readTimeout = readTimeout;
            return this;
        }

        /**
         * 设置单次请求四维上下文出站透传控制开关
         * 设置为 false 时，出站请求将严格剥离内网私有标识（X-Tenant-Id, X-User-Id 等），支持在调用外部公网接口时实现非信任域隔离
         *
         * @param propagateContext 是否透传上下文
         * @return 当前构建器
         */
        public Builder propagateContext(Boolean propagateContext) {
            this.propagateContext = propagateContext;
            return this;
        }

        /**
         * 显式指定当前请求所归属的命名客户端标识（用于绑定专属超时、重试与域隔离策略）
         *
         * @param clientName 客户端标识
         * @return 当前构建器
         */
        public Builder clientName(String clientName) {
            this.clientName = clientName;
            return this;
        }

        /**
         * 完成构建并返回不可变 RequestOptions 载荷实例
         *
         * @return 不可变 RequestOptions 实例
         */
        public RequestOptions build() {
            return new RequestOptions(headers, queryParams, uriVariables, readTimeout, propagateContext, clientName);
        }
    }
}
