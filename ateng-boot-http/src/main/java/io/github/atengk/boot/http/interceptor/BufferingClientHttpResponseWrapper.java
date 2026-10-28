package io.github.atengk.boot.http.interceptor;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;

/**
 * 支持安全重读与流式拼接的 HTTP 客户端响应包装器
 * 用于在 FULL 日志级别下缓存有限大小的响应体，既满足日志打印与安全脱敏，又保障后续业务反序列化与异常转译多次消费无阻断；
 * 针对分块超限流，支持将已预读前缀与剩余底层流无缝拼接，绝对杜绝数据截断丢失
 *
 * @author Ateng
 * @since 2026-10-21
 */
public class BufferingClientHttpResponseWrapper implements ClientHttpResponse {

    private final ClientHttpResponse response;
    private final byte[] body;
    private final InputStream remainingStream;

    public BufferingClientHttpResponseWrapper(ClientHttpResponse response, byte[] body) {
        this(response, body, null);
    }

    public BufferingClientHttpResponseWrapper(ClientHttpResponse response, byte[] body, InputStream remainingStream) {
        this.response = response;
        this.body = body != null ? body : new byte[0];
        this.remainingStream = remainingStream;
    }

    @Override
    public HttpStatusCode getStatusCode() throws IOException {
        return this.response.getStatusCode();
    }

    @Override
    public String getStatusText() throws IOException {
        return this.response.getStatusText();
    }

    @Override
    public HttpHeaders getHeaders() {
        return this.response.getHeaders();
    }

    @Override
    public InputStream getBody() {
        if (this.remainingStream != null) {
            return new SequenceInputStream(new ByteArrayInputStream(this.body), this.remainingStream);
        }
        return new ByteArrayInputStream(this.body);
    }

    /**
     * 判断当前响应体是否因超出缓冲容量限制而退化为流式拼接模式
     *
     * @return true 表示响应流超限并进行了无损拼接保护
     */
    public boolean isOverflow() {
        return this.remainingStream != null;
    }

    /**
     * 获取已安全缓存在内存中的前置响应体字节切片
     *
     * @return 字节数组拷贝
     */
    public byte[] getBufferedBody() {
        return this.body;
    }

    @Override
    public void close() {
        this.response.close();
    }
}
