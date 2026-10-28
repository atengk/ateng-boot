package io.github.atengk.boot.web.filter;

import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.trace.TraceFilter;
import io.github.atengk.boot.web.trace.TraceUtil;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * HTTP Payload 双向安全采样打印与截断防 OOM 过滤器
 * 支持对请求体与响应体执行双向安全采样并在出口处输出单行聚合审计日志
 * 内建 MIME 过滤防卫（自动跳过大文件与二进制媒体流）、单次最大载荷截断防 OOM、敏感凭据正则掩码及状态码自适应提升
 *
 * @author Ateng
 * @since 2026-10-18
 */
public class PayloadLoggingFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(PayloadLoggingFilter.class);

    /**
     * 默认过滤器执行优先级：位于可重读管道之后，慢请求告警过滤器之前
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    public static final DataSize DEFAULT_MAX_PAYLOAD_SIZE = DataSize.ofKilobytes(4);
    public static final String BINARY_STREAM_PLACEHOLDER = "[BINARY_STREAM]";
    public static final String TRUNCATED_SUFFIX = " ... [TRUNCATED]";

    // JSON 键值对掩码正则：匹配 "key": "value" 或 "key": value（支持嵌套转义双引号）
    private static final Pattern JSON_SENSITIVE_PATTERN = Pattern.compile(
            "(?i)(\"(?:password|passwd|pwd|secret|token|accessToken|access_token|refreshToken|refresh_token|authorization|auth)\"\\s*:\\s*)(?:\"(?:\\\\.|[^\"\\\\])*\"|[^,}\\]\\s]+)",
            Pattern.CASE_INSENSITIVE
    );

    // 表单或 URL 参数键值对掩码正则：匹配 key=value
    private static final Pattern FORM_SENSITIVE_PATTERN = Pattern.compile(
            "(?i)(^|&)((?:password|passwd|pwd|secret|token|accessToken|access_token|refreshToken|refresh_token|authorization|auth)=)([^&]*)",
            Pattern.CASE_INSENSITIVE
    );

    // XML 标签掩码正则：匹配 <key>value</key>
    private static final Pattern XML_SENSITIVE_PATTERN = Pattern.compile(
            "(?i)(<(?:password|passwd|pwd|secret|token|accessToken|access_token|refreshToken|refresh_token|authorization|auth)[^>]*>)[^<]*(</(?:password|passwd|pwd|secret|token|accessToken|access_token|refreshToken|refresh_token|authorization|auth)>)",
            Pattern.CASE_INSENSITIVE
    );

    private final DataSize maxPayloadSize;
    private final List<String> excludeUrls;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public PayloadLoggingFilter() {
        this(DEFAULT_MAX_PAYLOAD_SIZE, Collections.emptyList());
    }

    public PayloadLoggingFilter(DataSize maxPayloadSize) {
        this(maxPayloadSize, Collections.emptyList());
    }

    public PayloadLoggingFilter(WebProperties.PayloadLogProperties properties) {
        this(
                properties != null && properties.getMaxPayloadSize() != null ? properties.getMaxPayloadSize() : DEFAULT_MAX_PAYLOAD_SIZE,
                properties != null && properties.getExcludeUrls() != null ? properties.getExcludeUrls() : Collections.emptyList()
        );
    }

    public PayloadLoggingFilter(DataSize maxPayloadSize, List<String> excludeUrls) {
        this.maxPayloadSize = maxPayloadSize != null ? maxPayloadSize : DEFAULT_MAX_PAYLOAD_SIZE;
        this.excludeUrls = excludeUrls != null ? new ArrayList<>(excludeUrls) : new ArrayList<>();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    public DataSize getMaxPayloadSize() {
        return maxPayloadSize;
    }

    public List<String> getExcludeUrls() {
        return Collections.unmodifiableList(excludeUrls);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isExcluded(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        HttpServletRequest requestToUse = wrapRequestIfNecessary(request);
        ContentCachingResponseWrapper responseWrapper = (response instanceof ContentCachingResponseWrapper)
                ? (ContentCachingResponseWrapper) response
                : new ContentCachingResponseWrapper(response);

        long startTime = System.currentTimeMillis();
        Throwable error = null;
        try {
            filterChain.doFilter(requestToUse, responseWrapper);
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            long costTime = System.currentTimeMillis() - startTime;
            if (request.isAsyncStarted()) {
                final Throwable finalError = error;
                request.getAsyncContext().addListener(new AsyncListener() {
                    @Override
                    public void onComplete(AsyncEvent event) throws IOException {
                        try {
                            logPayload(requestToUse, responseWrapper, costTime, finalError);
                        } finally {
                            responseWrapper.copyBodyToResponse();
                        }
                    }

                    @Override
                    public void onTimeout(AsyncEvent event) {
                    }

                    @Override
                    public void onError(AsyncEvent event) {
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event) {
                    }
                });
            } else {
                try {
                    logPayload(requestToUse, responseWrapper, costTime, error);
                } finally {
                    responseWrapper.copyBodyToResponse();
                }
            }
        }
    }

    /**
     * 判断当前请求是否命中排除白名单
     *
     * @param request HTTP 请求
     * @return true 表示需要排除
     */
    protected boolean isExcluded(HttpServletRequest request) {
        if (excludeUrls.isEmpty()) {
            return false;
        }
        String uri = request.getRequestURI();
        String servletPath = request.getServletPath();
        for (String pattern : excludeUrls) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
            if (servletPath != null && pathMatcher.match(pattern, servletPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 当上游未装配 ContentCachingFilter 时，防御性将写请求包装为 CachedBodyHttpServletRequest 保证流可重读
     *
     * @param request 原始请求
     * @return 升级后的请求包装器
     * @throws IOException IO 异常
     */
    protected HttpServletRequest wrapRequestIfNecessary(HttpServletRequest request) throws IOException {
        String contentType = request.getContentType();
        boolean isMultipart = contentType != null && contentType.toLowerCase().startsWith("multipart/");
        boolean isForm = contentType != null && contentType.toLowerCase().startsWith("application/x-www-form-urlencoded");
        String method = request.getMethod();
        boolean isModifyingMethod = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);

        if (!isMultipart && !isForm && isModifyingMethod && !(request instanceof CachedBodyHttpServletRequest)) {
            int limit = (int) Math.max(maxPayloadSize.toBytes(), 2 * 1024 * 1024L);
            return new CachedBodyHttpServletRequest(request, limit);
        }
        return request;
    }

    /**
     * 单行聚合输出 HTTP 采样日志
     */
    protected void logPayload(HttpServletRequest request,
                              ContentCachingResponseWrapper response,
                              long costTime,
                              Throwable error) {
        String traceId = TraceUtil.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = request.getHeader("X-Trace-Id");
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = "-";
        }

        String method = request.getMethod() != null ? request.getMethod() : "UNKNOWN";
        String uri = request.getRequestURI() != null ? request.getRequestURI() : "";
        int status = (error != null && response.getStatus() < 400)
                ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                : response.getStatus();

        String reqBody = extractRequestBody(request);
        String respBody = extractResponseBody(response);

        if (status >= 400) {
            log.warn("[HTTP_PAYLOAD] traceId=[{}], method=[{}], uri=[{}], status=[{}], cost=[{}ms], reqBody=[{}], respBody=[{}]",
                    traceId, method, uri, status, costTime, reqBody, respBody);
        } else {
            log.info("[HTTP_PAYLOAD] traceId=[{}], method=[{}], uri=[{}], status=[{}], cost=[{}ms], reqBody=[{}], respBody=[{}]",
                    traceId, method, uri, status, costTime, reqBody, respBody);
        }
    }

    /**
     * 提取并安全清洗请求载荷
     */
    protected String extractRequestBody(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (isBinaryOrMultipart(contentType)) {
            return BINARY_STREAM_PLACEHOLDER;
        }

        byte[] bodyBytes = null;
        if (request instanceof CachedBodyHttpServletRequest cachedReq) {
            bodyBytes = cachedReq.getCachedBody();
        }

        Charset charset = getCharset(request.getCharacterEncoding());

        // 若请求体流未缓存但属于表单提交，从已解析的 parameterMap 提取键值对
        if ((bodyBytes == null || bodyBytes.length == 0) && isForm(contentType)) {
            java.util.Map<String, String[]> parameterMap = request.getParameterMap();
            if (parameterMap != null && !parameterMap.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (java.util.Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
                    for (String val : entry.getValue()) {
                        if (sb.length() > 0) {
                            sb.append("&");
                        }
                        sb.append(entry.getKey()).append("=").append(val);
                    }
                }
                return formatPayload(sb.toString().getBytes(charset), charset);
            }
        }

        if (bodyBytes == null || bodyBytes.length == 0) {
            return "";
        }

        if (contentType != null && !isTextOrJson(contentType)) {
            return BINARY_STREAM_PLACEHOLDER;
        }

        return formatPayload(bodyBytes, charset);
    }

    /**
     * 提取并安全清洗响应载荷
     */
    protected String extractResponseBody(ContentCachingResponseWrapper response) {
        String contentType = response.getContentType();
        if (isBinaryOrMultipart(contentType)) {
            return BINARY_STREAM_PLACEHOLDER;
        }

        byte[] bodyBytes = response.getContentAsByteArray();
        if (bodyBytes.length == 0) {
            return "";
        }

        if (contentType != null && !isTextOrJson(contentType)) {
            return BINARY_STREAM_PLACEHOLDER;
        }

        if (contentType == null && !isLikelyText(bodyBytes)) {
            return BINARY_STREAM_PLACEHOLDER;
        }

        Charset charset = getCharset(response.getCharacterEncoding());
        return formatPayload(bodyBytes, charset);
    }

    /**
     * 格式化载荷字节，执行截断与凭据掩码
     */
    protected String formatPayload(byte[] bytes, Charset charset) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        int limit = (int) maxPayloadSize.toBytes();
        boolean truncated = bytes.length > limit;
        int lengthToDecode = truncated ? limit : bytes.length;

        String content = new String(bytes, 0, lengthToDecode, charset);
        String masked = maskSensitiveData(content);

        if (truncated) {
            return masked + TRUNCATED_SUFFIX;
        }
        return masked;
    }

    /**
     * 对 JSON、Form 与 XML 格式字符串中的敏感凭据执行正则掩码
     *
     * @param payload 原始载荷字符串
     * @return 掩码后的载荷字符串
     */
    public static String maskSensitiveData(String payload) {
        if (payload == null || payload.isBlank()) {
            return payload;
        }
        String masked = JSON_SENSITIVE_PATTERN.matcher(payload).replaceAll("$1\"***\"");
        masked = FORM_SENSITIVE_PATTERN.matcher(masked).replaceAll("$1$2***");
        masked = XML_SENSITIVE_PATTERN.matcher(masked).replaceAll("$1***$2");
        return masked;
    }

    /**
     * 校验是否为文本或 JSON 类型 MIME
     */
    public static boolean isTextOrJson(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String lower = contentType.toLowerCase();
        return lower.contains("application/json")
                || lower.contains("application/problem+json")
                || lower.contains("application/xml")
                || lower.contains("text/xml")
                || lower.contains("+json")
                || lower.contains("+xml")
                || lower.contains("text/plain")
                || lower.contains("text/html")
                || lower.startsWith("text/")
                || lower.contains("application/x-www-form-urlencoded");
    }

    /**
     * 校验是否为表单提交
     */
    public static boolean isForm(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("application/x-www-form-urlencoded");
    }

    /**
     * 校验是否为二进制流、大文件或分块下载
     */
    public static boolean isBinaryOrMultipart(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String lower = contentType.toLowerCase();
        return lower.startsWith("multipart/")
                || lower.contains("application/octet-stream")
                || lower.startsWith("image/")
                || lower.startsWith("audio/")
                || lower.startsWith("video/")
                || lower.contains("application/pdf")
                || lower.contains("application/zip")
                || lower.contains("application/gzip")
                || lower.contains("text/event-stream");
    }

    private static boolean isLikelyText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return true;
        }
        int checkLen = Math.min(bytes.length, 128);
        for (int i = 0; i < checkLen; i++) {
            if (bytes[i] == 0) {
                return false;
            }
        }
        return true;
    }

    private Charset getCharset(String characterEncoding) {
        if (characterEncoding != null && !characterEncoding.isBlank()) {
            try {
                return Charset.forName(characterEncoding);
            } catch (Exception ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }
}
