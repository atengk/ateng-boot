package io.github.atengk.boot.web.sign.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 可重复读取请求体的 HttpServletRequest 包装器
 * 缓存请求原始输入流，确保切面验签与 Controller 参数反序列化可安全并发复用请求体，内置载荷上限防爆防护
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    /**
     * 默认最大允许缓存的请求体大小（2MB）
     */
    public static final int DEFAULT_MAX_BODY_SIZE = 2 * 1024 * 1024;

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        this(request, DEFAULT_MAX_BODY_SIZE);
    }

    public CachedBodyHttpServletRequest(HttpServletRequest request, int maxBodySize) throws IOException {
        super(request);
        int limit = maxBodySize > 0 ? maxBodySize : DEFAULT_MAX_BODY_SIZE;
        int contentLength = request.getContentLength();
        if (contentLength > limit) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                    "请求体大小超过允许的最大限制: " + limit + " 字节");
        }

        InputStream is = request.getInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(contentLength > 0 ? Math.min(contentLength, limit) : 1024);
        byte[] buffer = new byte[4096];
        int bytesRead;
        int totalBytes = 0;
        while ((bytesRead = is.read(buffer)) != -1) {
            totalBytes += bytesRead;
            if (totalBytes > limit) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                        "请求体大小超过允许的最大限制: " + limit + " 字节");
            }
            baos.write(buffer, 0, bytesRead);
        }
        this.cachedBody = baos.toByteArray();
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream bais = new ByteArrayInputStream(this.cachedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
            }

            @Override
            public int read() {
                return bais.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return bais.read(b, off, len);
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    /**
     * 获取缓存的请求体原始字节数组
     *
     * @return 请求体字节数组副本
     */
    public byte[] getCachedBody() {
        return this.cachedBody.clone();
    }
}
