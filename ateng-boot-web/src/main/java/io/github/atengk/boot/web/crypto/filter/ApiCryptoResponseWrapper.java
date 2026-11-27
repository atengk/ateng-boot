package io.github.atengk.boot.web.crypto.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.filter.PayloadLoggingFilter;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 出站响应明文缓冲包装器
 * 仅对已激活 {@link io.github.atengk.boot.web.crypto.annotation.ApiEncrypt} 的端点按需启用，
 * 拦截明文响应输出并延后 {@code Content-Length} 写入以避免密文信封长度不一致导致截断；
 * 一旦探测到二进制流或 SSE 事件流自动切换为透传模式防 OOM；非流式响应超限严禁泄露明文，立即丢弃缓冲并抛出 413 异常阻断
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class ApiCryptoResponseWrapper extends HttpServletResponseWrapper {

    private final int maxBufferBytes;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
    private ServletOutputStream outputStream;
    private PrintWriter writer;
    private boolean passthrough;
    private boolean payloadTooLarge;

    public ApiCryptoResponseWrapper(HttpServletResponse response, int maxBufferBytes) {
        super(response);
        this.maxBufferBytes = maxBufferBytes > 0 ? maxBufferBytes : 2 * 1024 * 1024;
    }

    public boolean isPassthrough() {
        return passthrough;
    }

    /**
     * 判断当前缓冲过程中是否因非二进制响应体超出最大上限而触发 413 阻断
     *
     * @return true 表示出站明文超限被阻断
     */
    public boolean isPayloadTooLarge() {
        return payloadTooLarge;
    }

    @Override
    public void setContentType(String type) {
        super.setContentType(type);
        if (PayloadLoggingFilter.isBinaryOrMultipart(type)) {
            this.passthrough = true;
        }
    }

    @Override
    public void setContentLength(int len) {
        if (passthrough) {
            super.setContentLength(len);
        }
    }

    @Override
    public void setContentLengthLong(long len) {
        if (passthrough) {
            super.setContentLengthLong(len);
        }
    }

    @Override
    public void setHeader(String name, String value) {
        if (!passthrough && "Content-Length".equalsIgnoreCase(name)) {
            return;
        }
        if ("Content-Type".equalsIgnoreCase(name) && PayloadLoggingFilter.isBinaryOrMultipart(value)) {
            this.passthrough = true;
        }
        super.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        if (!passthrough && "Content-Length".equalsIgnoreCase(name)) {
            return;
        }
        if ("Content-Type".equalsIgnoreCase(name) && PayloadLoggingFilter.isBinaryOrMultipart(value)) {
            this.passthrough = true;
        }
        super.addHeader(name, value);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (this.outputStream == null) {
            this.outputStream = new BufferedServletOutputStream();
        }
        return this.outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (this.writer == null) {
            String encoding = getCharacterEncoding();
            Charset charset;
            try {
                charset = (encoding != null && !encoding.isBlank()) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            } catch (Exception e) {
                charset = StandardCharsets.UTF_8;
            }
            this.writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset), false);
        }
        return this.writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (this.writer != null) {
            this.writer.flush();
        }
        if (passthrough) {
            flushBufferToUnderlying();
            super.flushBuffer();
        }
    }

    @Override
    public void resetBuffer() {
        if (!passthrough) {
            this.buffer.reset();
        }
        super.resetBuffer();
    }

    @Override
    public void reset() {
        if (!passthrough) {
            this.buffer.reset();
            this.writer = null;
            this.outputStream = null;
        }
        super.reset();
    }

    /**
     * 获取当前缓冲的全部响应字节数组
     *
     * @return 响应体字节数组
     */
    public byte[] getContentAsByteArray() {
        if (this.writer != null) {
            this.writer.flush();
        }
        return this.buffer.toByteArray();
    }

    private void switchToPassthrough() throws IOException {
        if (!passthrough) {
            passthrough = true;
            flushBufferToUnderlying();
        }
    }

    private void flushBufferToUnderlying() throws IOException {
        if (buffer.size() > 0) {
            ServletOutputStream realOut = getResponse().getOutputStream();
            buffer.writeTo(realOut);
            buffer.reset();
            realOut.flush();
        }
    }

    private class BufferedServletOutputStream extends ServletOutputStream {

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
        }

        @Override
        public void write(int b) throws IOException {
            if (passthrough) {
                getResponse().getOutputStream().write(b);
                return;
            }
            if (PayloadLoggingFilter.isBinaryOrMultipart(getContentType())) {
                switchToPassthrough();
                getResponse().getOutputStream().write(b);
                return;
            }
            if (buffer.size() + 1 > maxBufferBytes) {
                abortOverflow();
            }
            buffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (passthrough) {
                getResponse().getOutputStream().write(b, off, len);
                return;
            }
            if (len > 0 && PayloadLoggingFilter.isBinaryOrMultipart(getContentType())) {
                switchToPassthrough();
                getResponse().getOutputStream().write(b, off, len);
                return;
            }
            if (len > 0 && buffer.size() + len > maxBufferBytes) {
                abortOverflow();
            }
            buffer.write(b, off, len);
        }

        private void abortOverflow() {
            payloadTooLarge = true;
            buffer.reset();
            throw new BusinessException(CommonResultCode.PAYLOAD_TOO_LARGE,
                    "出站加密响应体大小超过允许的最大限制: " + maxBufferBytes + " 字节");
        }
    }
}
