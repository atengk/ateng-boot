package io.github.atengk.boot.web.crypto.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 解密后明文请求体包装器
 * 将 {@link io.github.atengk.boot.web.crypto.filter.ApiCryptoFilter} 解密还原后的 UTF-8 明文 JSON 字节流替换进请求体，
 * 供下游 Spring MVC {@code @RequestBody}、{@code @Valid} 与 XSS Jackson 反序列化器透明消费
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class DecryptedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] decryptedBody;

    public DecryptedBodyHttpServletRequest(HttpServletRequest request, byte[] decryptedBody) {
        super(request);
        this.decryptedBody = decryptedBody != null ? decryptedBody.clone() : new byte[0];
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream bais = new ByteArrayInputStream(this.decryptedBody);
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
        Charset charset;
        try {
            charset = (encoding != null && !encoding.isBlank()) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        } catch (Exception e) {
            charset = StandardCharsets.UTF_8;
        }
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    @Override
    public int getContentLength() {
        return this.decryptedBody.length;
    }

    @Override
    public long getContentLengthLong() {
        return this.decryptedBody.length;
    }

    @Override
    public String getContentType() {
        String original = super.getContentType();
        if (original != null && !original.isBlank()) {
            return original;
        }
        return "application/json;charset=UTF-8";
    }

    /**
     * 获取解密后的明文请求体字节数组副本
     *
     * @return 明文字节数组副本
     */
    public byte[] getDecryptedBody() {
        return this.decryptedBody.clone();
    }
}
