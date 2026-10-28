package io.github.atengk.boot.http.error;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * HTTP 异常响应统一转译处理器
 * 拦截 4xx 与 5xx 状态码，安全提取截断响应体并封装抛出 HttpRemoteException
 *
 * @author Ateng
 * @since 2026-10-21
 */
public class HttpErrorHandler implements ResponseErrorHandler {

    private static final int MAX_BODY_CHARS = 2048;
    private static final String TRUNCATED_SUFFIX = "... [truncated]";
    private static final String BINARY_CONTENT_PLACEHOLDER = "[binary content omitted]";

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
        handleError(null, null, response);
    }

    @Override
    public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
        int statusCode = response.getStatusCode().value();
        String responseBody = extractResponseBody(response);
        throw new HttpRemoteException(statusCode, method, url, responseBody);
    }

    /**
     * 安全提取不超过 2048 字符的响应体文本，杜绝内存爆满
     *
     * @param response HTTP 客户端响应对象
     * @return 截断后的响应体字符串或占位文本
     */
    protected String extractResponseBody(ClientHttpResponse response) {
        try {
            HttpHeaders headers = response.getHeaders();
            MediaType contentType = headers.getContentType();
            if (isBinaryContent(contentType)) {
                return BINARY_CONTENT_PLACEHOLDER;
            }

            InputStream bodyStream = response.getBody();
            if (bodyStream == null) {
                return null;
            }

            Charset charset = (contentType != null && contentType.getCharset() != null)
                    ? contentType.getCharset()
                    : StandardCharsets.UTF_8;

            char[] buffer = new char[MAX_BODY_CHARS];
            StringBuilder sb = new StringBuilder();
            try (Reader reader = new BufferedReader(new InputStreamReader(bodyStream, charset))) {
                int readChars = 0;
                while (readChars < MAX_BODY_CHARS) {
                    int n = reader.read(buffer, readChars, MAX_BODY_CHARS - readChars);
                    if (n == -1) {
                        break;
                    }
                    readChars += n;
                }
                if (readChars > 0) {
                    sb.append(buffer, 0, readChars);
                    // 探测流中是否还有后续字符
                    if (reader.read() != -1) {
                        sb.append(TRUNCATED_SUFFIX);
                    }
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        } catch (Exception e) {
            return "[unable to read response body: " + e.getMessage() + "]";
        }
    }

    /**
     * 判断响应是否属于二进制多媒体流
     *
     * @param contentType 响应头中的 Content-Type
     * @return 若为二进制内容则返回 true
     */
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
}
