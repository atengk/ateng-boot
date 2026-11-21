package io.github.atengk.boot.sms.provider;

import io.github.atengk.boot.core.concurrent.ConcurrentLruCache;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.sms.exception.SmsResultCode;
import io.github.atengk.boot.sms.exception.SmsSendException;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.properties.SmsProxyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 基于 Spring 6 RestClient 的云厂商 HTTP 短信驱动抽象基类
 * 统一收敛有界 LRU 网络客户端构建与代理配置、端点协议头防御补全、无状态瞬态重试与 JSON 响应解析通用逻辑
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
public abstract class AbstractHttpSmsProvider implements SmsProvider {

    /**
     * 默认 RestClient 缓存最大条目容量（防动态代理与超时组合引发内存膨胀）
     */
    public static final int DEFAULT_MAX_CLIENT_CACHE_SIZE = 256;

    protected final RestClient.Builder restClientBuilder;
    private final ConcurrentLruCache<ClientCacheKey, RestClient> restClientCache;

    protected AbstractHttpSmsProvider(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, DEFAULT_MAX_CLIENT_CACHE_SIZE);
    }

    protected AbstractHttpSmsProvider(RestClient.Builder restClientBuilder, int maxClientCacheSize) {
        this.restClientBuilder = restClientBuilder != null ? restClientBuilder : RestClient.builder();
        this.restClientCache = new ConcurrentLruCache<>(maxClientCacheSize > 0 ? maxClientCacheSize : DEFAULT_MAX_CLIENT_CACHE_SIZE);
    }

    /**
     * 根据渠道配置（含超时时长与独立 HTTP 代理）获取或构建复用 RestClient 实例
     *
     * @param channel 目标短信渠道
     * @return RestClient 实例
     */
    protected RestClient buildRestClient(SmsChannel channel) {
        Duration timeout = channel != null ? channel.getTimeout() : null;
        SmsProxyProperties proxy = channel != null ? channel.getProxy() : null;
        return buildRestClient(timeout, proxy);
    }

    /**
     * 根据渠道指定的超时时间获取或构建复用 RestClient 实例
     *
     * @param timeout 超时时长
     * @return RestClient 实例
     */
    protected RestClient buildRestClient(Duration timeout) {
        return buildRestClient(timeout, null);
    }

    /**
     * 根据超时时间与代理配置从有界 LRU 缓存获取或创建 RestClient 实例
     *
     * @param timeout 超时时长
     * @param proxy   代理配置（可为 null）
     * @return RestClient 实例
     */
    protected RestClient buildRestClient(Duration timeout, SmsProxyProperties proxy) {
        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofSeconds(10);
        ClientCacheKey cacheKey = ClientCacheKey.of(effectiveTimeout, proxy);
        return restClientCache.computeIfAbsent(cacheKey, key -> createRestClient(effectiveTimeout, proxy));
    }

    /**
     * 创建新的 RestClient 实例（无代理）
     *
     * @param timeout 超时时长
     * @return 新建的 RestClient 实例
     */
    protected RestClient createRestClient(Duration timeout) {
        return createRestClient(timeout, null);
    }

    /**
     * 创建新的 RestClient 实例（支持可选 HTTP 正向代理与 Basic 认证）
     *
     * @param timeout 超时时长
     * @param proxy   代理配置（可为 null）
     * @return 新建的 RestClient 实例
     */
    protected RestClient createRestClient(Duration timeout, SmsProxyProperties proxy) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        if (timeout != null) {
            factory.setConnectTimeout(timeout);
            factory.setReadTimeout(timeout);
        }

        RestClient.Builder builder = restClientBuilder.clone().requestFactory(factory);
        if (proxy != null && proxy.isEnabled()) {
            BusinessAssert.hasText(proxy.getHost(), "启用渠道 HTTP 代理时 host 不能为空");
            int port = proxy.getPort();
            BusinessAssert.isTrue(port > 0 && port <= 65535,
                    () -> "启用渠道 HTTP 代理时 port 必须在 1~65535 之间，当前值: " + port);

            factory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.getHost().trim(), port)));

            if (StringUtils.hasText(proxy.getUsername())) {
                String credentials = proxy.getUsername().trim() + ":" + (proxy.getPassword() != null ? proxy.getPassword() : "");
                String basicToken = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                builder.requestInterceptor((req, body, execution) -> {
                    if (!req.getHeaders().containsKey("Proxy-Authorization")) {
                        req.getHeaders().set("Proxy-Authorization", basicToken);
                    }
                    return execution.execute(req, body);
                });
            }
        }
        return builder.build();
    }

    /**
     * 对配置端点执行协议头防御性补全与格式校验
     * 缺省配置返回默认公网端点；未显式声明 {@code http://} 或 {@code https://} 时自动补全 {@code https://}
     *
     * @param endpoint        渠道配置的自定义端点
     * @param defaultEndpoint 厂商官方默认公网端点
     * @return 规范化后的绝对 HTTP/HTTPS 端点 URL
     */
    protected String normalizeEndpoint(String endpoint, String defaultEndpoint) {
        if (!StringUtils.hasText(endpoint)) {
            return defaultEndpoint;
        }
        String clean = endpoint.trim();
        String lower = clean.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            clean = "https://" + clean;
        }
        URI parsed = URI.create(clean);
        BusinessAssert.hasText(parsed.getHost(), () -> "短信网关接入端点格式非法，缺少有效主机名: " + endpoint);
        return clean;
    }

    /**
     * 获取当前已缓存的 RestClient 实例数量（供单测与监控探测）
     *
     * @return 缓存实例数量
     */
    public int getCachedRestClientCount() {
        return restClientCache.size();
    }

    /**
     * 单渠道可重试任务执行回调
     *
     * @param <T> 返回值泛型
     */
    @FunctionalInterface
    public interface SmsExecutionCallback<T> {
        T execute() throws Exception;
    }

    /**
     * 线程安全无状态单渠道重试辅助执行器
     * 在当前方法调用栈上维护重试计数，严禁使用实例变量存储重试状态
     *
     * @param channel    当前发信渠道
     * @param actionName 操作名称
     * @param callback   执行回调
     * @param <T>        返回值泛型
     * @return 回调执行结果
     * @throws Exception 当重试耗尽或遭遇不可重试异常时抛出
     */
    protected <T> T executeWithRetry(SmsChannel channel, String actionName, SmsExecutionCallback<T> callback) throws Exception {
        int maxRetries = channel != null ? Math.max(0, channel.getMaxRetries()) : 0;
        Duration retryInterval = (channel != null && channel.getRetryInterval() != null)
                ? channel.getRetryInterval()
                : Duration.ofSeconds(1);

        RetryUtil.RetryConfig retryConfig = RetryUtil.RetryConfig.builder()
                .maxAttempts(maxRetries + 1)
                .delay(retryInterval)
                .multiplier(1.0)
                .maxDelay(retryInterval)
                .retryPredicate(th -> {
                    Throwable target = (th instanceof RuntimeException && th.getCause() instanceof Exception) ? th.getCause() : th;
                    return target instanceof Exception ex && isRetryableException(ex);
                })
                .onRetry((attempt, error) -> {
                    Throwable target = (error instanceof RuntimeException && error.getCause() instanceof Exception) ? error.getCause() : error;
                    if (log.isWarnEnabled()) {
                        log.warn("[AbstractHttpSmsProvider] 渠道 [{}] 执行 [{}] 遭遇瞬态异常 ({})，准备进行第 {}/{} 次重试 (等待: {}ms)",
                                channel != null ? channel.getName() : "unknown",
                                actionName,
                                target.getMessage(),
                                attempt,
                                maxRetries,
                                retryInterval.toMillis());
                    }
                })
                .build();

        try {
            return RetryUtil.execute(() -> {
                try {
                    return callback.execute();
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }, retryConfig);
        } catch (RuntimeException re) {
            if (re.getCause() instanceof Exception ex && !(re instanceof RestClientResponseException)) {
                throw ex;
            }
            throw re;
        }
    }

    /**
     * 判断异常是否为可重试的瞬态网络或服务端 5xx 故障
     *
     * @param ex 捕获的异常
     * @return true 表示可重试
     */
    protected boolean isRetryableException(Exception ex) {
        if (ex instanceof InterruptedException) {
            return false;
        }
        if (ex instanceof RestClientResponseException rre) {
            return rre.getStatusCode().is5xxServerError();
        }
        return ex instanceof IOException
                || ex instanceof ResourceAccessException
                || (ex.getCause() instanceof IOException);
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

    /**
     * 解析规范化 HTTP {@code Host} 请求头值（非 80/443 端口显式保留端口后缀）
     *
     * @param uri 目标 URI
     * @return Host 头值
     */
    protected static String resolveHostHeader(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return "";
        }
        int port = uri.getPort();
        return (port > 0 && port != 80 && port != 443) ? uri.getHost() + ":" + port : uri.getHost();
    }

    /**
     * 基于规范化端点与操作路径拼接完整请求 URL（兼容根地址、已带完整路径或前导前缀路径）
     *
     * @param normalizedEndpoint 规范化端点 URL
     * @param actionPath         期望追加或匹配的绝对路径（例如 {@code /api/v3/sendSms}）
     * @return 拼接后的完整 URL
     */
    protected static String resolveActionUrl(String normalizedEndpoint, String actionPath) {
        if (!StringUtils.hasText(actionPath)) {
            return normalizedEndpoint;
        }
        String path = actionPath.startsWith("/") ? actionPath : "/" + actionPath;
        String base = normalizedEndpoint.endsWith("/")
                ? normalizedEndpoint.substring(0, normalizedEndpoint.length() - 1)
                : normalizedEndpoint;
        String currentPath = URI.create(base).getPath();
        if (currentPath != null && currentPath.endsWith(path)) {
            return base;
        }
        return base + path;
    }

    /**
     * 执行严格 RFC 3986 URI 百分号编码（空格转 %20，保留 ~，转义 *）
     *
     * @param value 待编码文本
     * @return 编码后字符串
     */
    protected static String rfc3986Encode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    /**
     * 保留路径分隔符斜杠 {@code /} 的 RFC 3986 编码
     *
     * @param path URI 路径
     * @return 编码后的规范 URI 路径
     */
    protected static String rfc3986EncodeExceptSlash(String path) {
        if (!StringUtils.hasText(path)) {
            return "/";
        }
        return rfc3986Encode(path).replace("%2F", "/");
    }

    /**
     * 兼容可选父级前缀路径（如 {@code /msg}、{@code /sms}）拼接完整操作 URL
     *
     * @param normalizedEndpoint 规范化端点
     * @param optionalPrefix     可选前缀段（例如 {@code /msg}）
     * @param actionPath         子动作路径（例如 {@code /v1/send/json}）
     * @return 完整请求 URL
     */
    protected static String resolveActionUrlWithPrefix(String normalizedEndpoint, String optionalPrefix, String actionPath) {
        String base = normalizedEndpoint.endsWith("/")
                ? normalizedEndpoint.substring(0, normalizedEndpoint.length() - 1)
                : normalizedEndpoint;
        String currentPath = URI.create(base).getPath();
        String subPath = actionPath.startsWith("/") ? actionPath : "/" + actionPath;
        if (currentPath != null && currentPath.endsWith(optionalPrefix)) {
            return base + subPath;
        }
        return resolveActionUrl(base, optionalPrefix + subPath);
    }

    /**
     * 按 {@code k=v&...} 拼装表单或查询串参数
     *
     * @param params         有序参数键值表
     * @param urlEncodeKey   是否对 Key 执行 RFC 3986 编码
     * @param urlEncodeValue 是否对 Value 执行 RFC 3986 编码
     * @return 编码后的查询或表单正文串
     */
    protected static String encodeFormOrQuery(Map<String, String> params, boolean urlEncodeKey, boolean urlEncodeValue) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            String k = urlEncodeKey ? rfc3986Encode(entry.getKey()) : entry.getKey();
            String v = urlEncodeValue ? rfc3986Encode(entry.getValue()) : entry.getValue();
            sb.append(k).append('=').append(v);
        }
        return sb.toString();
    }

    /**
     * 规范化签名格式为 {@code 【签名】}
     *
     * @param signName 原始签名文本
     * @return 带中括号包裹的签名字符串
     */
    protected static String ensureBracketedSign(String signName) {
        String clean = signName != null ? signName.trim() : "";
        if (clean.isEmpty()) {
            return "";
        }
        if (!clean.startsWith("【")) {
            clean = "【" + clean;
        }
        if (!clean.endsWith("】")) {
            clean = clean + "】";
        }
        return clean;
    }

    /**
     * 为纯文本内容防御性前置补全 {@code 【签名】}（若正文已含 {@code 【...】} 则原样保持）
     *
     * @param signName 渠道或请求签名
     * @param content  纯文本正文
     * @return 携带签名的完整短信正文
     */
    protected static String prependSignIfAbsent(String signName, String content) {
        return prependSignIfAbsent(signName, content,
                "发送纯文本短信时必须在正文携带【签名】或在渠道/请求中指定 signName");
    }

    /**
     * 为文本内容防御性前置补全 {@code 【签名】}，支持自定义缺失签名的提示语
     *
     * @param signName          渠道或请求签名
     * @param content           短信正文或模板文本
     * @param missingSignPrompt 缺失签名时的错误提示
     * @return 携带签名的完整短信文本
     */
    protected static String prependSignIfAbsent(String signName, String content, String missingSignPrompt) {
        String cleanContent = content != null ? content.trim() : "";
        if (cleanContent.contains("【") && cleanContent.contains("】")) {
            return cleanContent;
        }
        BusinessAssert.hasText(signName, missingSignPrompt);
        return ensureBracketedSign(signName) + cleanContent;
    }

    /**
     * 从 HTTP 4xx/5xx 响应体提取 JSON 网关错误码与错误描述，转译为保留原始 {@code cause} 的 {@link SmsSendException}
     *
     * @param channel     目标渠道
     * @param vendorLabel 厂商名称
     * @param httpEx      HTTP 响应异常
     * @param codeKey     错误码 JSON 字段名（可为 null，默认取 {@code HTTP_<status>}）
     * @param msgKeys     候选错误描述 JSON 字段名
     */
    protected void throwJsonHttpErrorIfPresent(SmsChannel channel,
                                             String vendorLabel,
                                             RestClientResponseException httpEx,
                                             String codeKey,
                                             String... msgKeys) {
        Map<String, Object> errMap = parseJsonSafely(httpEx.getResponseBodyAsString());
        if (errMap.isEmpty()) {
            return;
        }
        Object codeVal = StringUtils.hasText(codeKey) ? errMap.get(codeKey) : null;
        String errMsg = null;
        if (msgKeys != null) {
            for (String mk : msgKeys) {
                Object mv = errMap.get(mk);
                if (mv != null && StringUtils.hasText(String.valueOf(mv))) {
                    errMsg = String.valueOf(mv);
                    break;
                }
            }
        }
        if (codeVal != null || errMsg != null) {
            String rawCode = codeVal != null ? String.valueOf(codeVal) : "HTTP_" + httpEx.getStatusCode().value();
            String finalMsg = errMsg != null ? errMsg : httpEx.getStatusText();
            throw new SmsSendException(channel.getName(), rawCode, finalMsg,
                    SmsResultCode.SMS_SEND_ERROR,
                    vendorLabel + "短信发送失败: [" + rawCode + "] " + finalMsg, httpEx);
        }
    }

    /**
     * 执行 HTTP POST 调用并统一转译 4xx/5xx、超时与 I/O 异常为保留原始 cause 的 {@link SmsSendException}
     *
     * @param channel       发信渠道
     * @param vendorLabel   厂商中文名称
     * @param actionName    操作名（用于重试与错误日志）
     * @param url           完整目标请求 URL
     * @param contentType   请求内容类型头值
     * @param headers       附加 HTTP 请求头（可为 null）
     * @param body          请求体字符串
     * @param errorTranslator HTTP 响应异常体自定义解析回调（可返回 null 走默认 HTTP 状态异常转译）
     * @return 响应正文字符串
     */
    protected String executePostWithRetry(SmsChannel channel,
                                          String vendorLabel,
                                          String actionName,
                                          String url,
                                          String contentType,
                                          Map<String, String> headers,
                                          String body,
                                          Consumer<RestClientResponseException> errorTranslator) {
        RestClient client = buildRestClient(channel);
        try {
            return executeWithRetry(channel, actionName, () -> {
                RestClient.RequestBodySpec spec = client.post()
                        .uri(URI.create(url));
                if (StringUtils.hasText(contentType)) {
                    spec.header("Content-Type", contentType);
                }
                if (headers != null && !headers.isEmpty()) {
                    headers.forEach((k, v) -> {
                        if (StringUtils.hasText(k) && v != null) {
                            spec.header(k, v);
                        }
                    });
                }
                if (body != null) {
                    spec.body(body);
                }
                return spec.retrieve().body(String.class);
            });
        } catch (RestClientResponseException e) {
            if (errorTranslator != null) {
                errorTranslator.accept(e);
            }
            throw new SmsSendException(channel.getName(), "HTTP_" + e.getStatusCode().value(),
                    e.getStatusText(), SmsResultCode.SMS_SEND_ERROR,
                    vendorLabel + "短信网关响应异常: " + e.getMessage(), e);
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception e) {
            throw new SmsSendException(channel.getName(), "NETWORK_ERROR", e.getMessage(),
                    SmsResultCode.SMS_SEND_ERROR, vendorLabel + "短信网络通信异常: " + e.getMessage(), e);
        }
    }

    private record ClientCacheKey(Duration timeout,
                                  boolean proxyEnabled,
                                  String proxyHost,
                                  int proxyPort,
                                  String proxyUsername,
                                  String proxyPassword) {

        static ClientCacheKey of(Duration timeout, SmsProxyProperties proxy) {
            if (proxy == null || !proxy.isEnabled()) {
                return new ClientCacheKey(timeout, false, null, 0, null, null);
            }
            return new ClientCacheKey(
                    timeout,
                    true,
                    proxy.getHost() != null ? proxy.getHost().trim() : null,
                    proxy.getPort(),
                    proxy.getUsername() != null ? proxy.getUsername().trim() : null,
                    proxy.getPassword()
            );
        }
    }
}
