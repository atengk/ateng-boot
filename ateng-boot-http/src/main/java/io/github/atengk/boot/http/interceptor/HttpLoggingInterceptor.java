package io.github.atengk.boot.http.interceptor;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.http.constant.HttpConstants;
import io.github.atengk.boot.http.properties.HttpLoggingLevel;
import io.github.atengk.boot.http.properties.HttpLoggingProperties;
import io.github.atengk.boot.http.properties.HttpProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 出站 HTTP 请求与响应结构化日志拦截器
 * 提供多级日志输出、终端可复现标准 cURL 调试命令、敏感 Header 动态掩码脱敏及大响应体容量安全防爆
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class HttpLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final String BINARY_BODY_PLACEHOLDER = "[binary content omitted]";
    private static final String STREAMING_BODY_PLACEHOLDER = "[streaming download body omitted]";
    private static final String EXCEEDED_CAPACITY_PLACEHOLDER = "[exceeds buffer capacity, body omitted]";
    private static final String TRUNCATED_MARKER = "... [truncated]";

    private final HttpProperties properties;

    public HttpLoggingInterceptor(HttpProperties properties) {
        this.properties = properties != null ? properties : new HttpProperties();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        HttpLoggingProperties logging = properties.getLogging();
        if (logging == null || !logging.isEnabled() || logging.getLevel() == HttpLoggingLevel.NONE) {
            return execution.execute(request, body);
        }

        HttpHeaders requestHeaders = request.getHeaders();
        // 1. 读取并剥离流式传输标记头（守护零内存缓冲不变量）
        boolean isStreamingDownload = extractBooleanHeader(requestHeaders, HttpConstants.HEADER_STREAM_TRANSFER);

        long startNano = System.nanoTime();
        ClientHttpResponse response = null;
        Throwable error = null;

        try {
            response = execution.execute(request, body);
            // 2. 在 FULL 级别下针对非流式安全响应体进行条件缓冲以支持流复用
            if (logging.getLevel() == HttpLoggingLevel.FULL && !isStreamingDownload) {
                response = bufferResponseIfSafe(response, logging);
            }
            return response;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNano);
            logExchange(request, body, response, error, costMs, logging, isStreamingDownload);
        }
    }

    /**
     * 针对普通请求进行有容量保护的响应体安全缓冲
     */
    protected ClientHttpResponse bufferResponseIfSafe(ClientHttpResponse response, HttpLoggingProperties logging) {
        try {
            HttpHeaders headers = response.getHeaders();
            MediaType contentType = headers.getContentType();
            if (isBinaryContent(contentType)) {
                return response;
            }

            long contentLength = headers.getContentLength();
            long maxCapacity = logging.getMaxBufferCapacity().toBytes();
            if (contentLength > maxCapacity) {
                return response;
            }

            InputStream in = response.getBody();
            if (in == null) {
                return response;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            long totalRead = 0;
            while ((read = in.read(buf)) != -1) {
                totalRead += read;
                if (totalRead > maxCapacity) {
                    // 超过最大缓冲容量限制，放弃包装直通返回以防爆内存
                    return response;
                }
                baos.write(buf, 0, read);
            }

            return new BufferingClientHttpResponseWrapper(response, baos.toByteArray());
        } catch (Exception ex) {
            log.debug("响应体安全缓冲失败，降级为直通流: {}", ex.getMessage());
            return response;
        }
    }

    /**
     * 执行结构化出站日志格式化输出
     */
    protected void logExchange(HttpRequest request,
                                byte[] requestBody,
                                ClientHttpResponse response,
                                Throwable error,
                                long costMs,
                                HttpLoggingProperties logging,
                                boolean isStreamingDownload) {
        HttpMethod method = request.getMethod();
        URI uri = request.getURI();
        HttpLoggingLevel level = logging.getLevel();

        if (error != null) {
            log.warn("HTTP {} {} -> FAILED: {} ({}ms)", method, uri, error.getMessage(), costMs);
            return;
        }

        try {
            int statusCode = response.getStatusCode().value();
            String statusText = response.getStatusText();

            if (level == HttpLoggingLevel.BASIC) {
                log.info("HTTP {} {} -> {} {} ({}ms)", method, uri, statusCode, statusText, costMs);
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n==================== [HTTP Outbound Exchange] ====================")
                    .append("\nRequest  : ").append(method).append(" ").append(uri)
                    .append("\nResponse : ").append(statusCode).append(" ").append(statusText).append(" (").append(costMs).append("ms)");

            // 打印请求头与响应头 (已脱敏)
            if (level == HttpLoggingLevel.HEADERS || level == HttpLoggingLevel.FULL) {
                sb.append("\nRequest Headers: ").append(formatMaskedHeaders(request.getHeaders(), logging.getMaskHeaders()));
                sb.append("\nResponse Headers: ").append(formatMaskedHeaders(response.getHeaders(), logging.getMaskHeaders()));
            }

            // 打印 cURL 脚本与响应体
            if (level == HttpLoggingLevel.FULL) {
                sb.append("\n\ncURL Reproduce:\n").append(generateCurlCommand(request, requestBody, logging));

                sb.append("\n\nResponse Body:\n");
                if (isStreamingDownload) {
                    sb.append(STREAMING_BODY_PLACEHOLDER);
                } else if (response instanceof BufferingClientHttpResponseWrapper buffered) {
                    Charset charset = resolveCharset(buffered.getHeaders().getContentType());
                    String bodyStr = StreamUtils.copyToString(buffered.getBody(), charset);
                    sb.append(truncateString(bodyStr, logging.getMaxBodyLength()));
                } else if (isBinaryContent(response.getHeaders().getContentType())) {
                    sb.append(BINARY_BODY_PLACEHOLDER);
                } else {
                    sb.append(EXCEEDED_CAPACITY_PLACEHOLDER);
                }
            }

            sb.append("\n==================================================================");
            log.info(sb.toString());
        } catch (Exception ex) {
            log.debug("出站日志格式化输出异常: {}", ex.getMessage());
        }
    }

    /**
     * 生成标准可直接在终端执行复现的 cURL 命令字符串
     */
    public String generateCurlCommand(HttpRequest request, byte[] body, HttpLoggingProperties logging) {
        StringBuilder curl = new StringBuilder("curl -X ").append(request.getMethod().name());
        curl.append(" '").append(request.getURI().toString()).append("'");

        HttpHeaders headers = request.getHeaders();
        List<String> maskHeaders = logging != null ? logging.getMaskHeaders() : Collections.emptyList();

        headers.forEach((headerName, values) -> {
            if (values != null) {
                for (String val : values) {
                    String maskedVal = maskHeaderValue(headerName, val, maskHeaders);
                    curl.append(" \\\n  -H '").append(headerName).append(": ").append(escapeSingleQuotes(maskedVal)).append("'");
                }
            }
        });

        if (body != null && body.length > 0) {
            MediaType contentType = headers.getContentType();
            if (isBinaryContent(contentType)) {
                curl.append(" \\\n  --data-binary '").append(BINARY_BODY_PLACEHOLDER).append("'");
            } else {
                Charset charset = resolveCharset(contentType);
                String bodyStr = new String(body, charset);
                int maxLen = logging != null ? logging.getMaxBodyLength() : 1024;
                String truncatedBody = truncateString(bodyStr, maxLen);
                curl.append(" \\\n  --data-raw '").append(escapeSingleQuotes(truncatedBody)).append("'");
            }
        }

        return curl.toString();
    }

    /**
     * 格式化并脱敏输出 HTTP Headers 摘要
     */
    protected String formatMaskedHeaders(HttpHeaders headers, List<String> maskHeaders) {
        if (headers == null || headers.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String key : headers.keySet()) {
            List<String> values = headers.get(key);
            if (!first) {
                sb.append(", ");
            }
            sb.append(key).append("=");
            if (values == null || values.isEmpty()) {
                sb.append("[]");
            } else {
                List<String> masked = new ArrayList<>();
                for (String val : values) {
                    masked.add(maskHeaderValue(key, val, maskHeaders));
                }
                sb.append(masked.size() == 1 ? masked.get(0) : masked);
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 对单个 Header 取值实施动态脱敏
     */
    public String maskHeaderValue(String headerName, String value, List<String> maskHeaders) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        if (maskHeaders == null || maskHeaders.isEmpty()) {
            return value;
        }

        boolean isSensitive = maskHeaders.stream()
                .anyMatch(maskKey -> maskKey.equalsIgnoreCase(headerName));

        if (!isSensitive) {
            return value;
        }

        // 针对 Authorization: Bearer <token> 语法定制脱敏
        if ("Authorization".equalsIgnoreCase(headerName) && value.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            String token = value.substring(7).trim();
            String maskedToken = maskTokenString(token);
            return "Bearer " + maskedToken;
        }

        return maskTokenString(value);
    }

    private String maskTokenString(String str) {
        int len = str.length();
        if (len <= 8) {
            return "******";
        }
        // 保留前后各 2 位，中间以星号掩码覆盖
        return DesensitizeUtil.mask(str, 2, len - 2, '*');
    }

    protected boolean isBinaryContent(MediaType contentType) {
        if (contentType == null) {
            return false;
        }
        if (MediaType.APPLICATION_OCTET_STREAM.includes(contentType)) {
            return true;
        }
        String type = contentType.getType().toLowerCase(Locale.ROOT);
        if ("image".equals(type) || "audio".equals(type) || "video".equals(type)) {
            return true;
        }
        String subtype = contentType.getSubtype().toLowerCase(Locale.ROOT);
        return subtype.contains("zip") || subtype.contains("pdf") || subtype.contains("gzip")
                || subtype.contains("tar") || subtype.contains("octet-stream");
    }

    private Charset resolveCharset(MediaType contentType) {
        return (contentType != null && contentType.getCharset() != null)
                ? contentType.getCharset()
                : StandardCharsets.UTF_8;
    }

    private String truncateString(String str, int maxLen) {
        if (str == null || str.length() <= maxLen) {
            return str;
        }
        return str.substring(0, maxLen) + TRUNCATED_MARKER;
    }

    private String escapeSingleQuotes(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("'", "'\\''");
    }

    private boolean extractBooleanHeader(HttpHeaders headers, String headerName) {
        List<String> values = headers.remove(headerName);
        if (values != null && !values.isEmpty()) {
            String val = values.get(0);
            return Boolean.parseBoolean(val.trim());
        }
        return false;
    }
}
