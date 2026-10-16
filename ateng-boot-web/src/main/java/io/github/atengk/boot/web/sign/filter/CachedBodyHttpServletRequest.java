package io.github.atengk.boot.web.sign.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 可重复读取请求体的 HttpServletRequest 包装器
 * 缓存请求原始输入流，确保切面验签与 Controller 参数反序列化可安全并发复用请求体
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.cachedBody = request.getInputStream().readAllBytes();
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
