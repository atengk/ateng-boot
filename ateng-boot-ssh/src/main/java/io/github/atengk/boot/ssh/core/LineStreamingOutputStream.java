package io.github.atengk.boot.ssh.core;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 实时按行流式消费输出流
 * <p>
 * 逐字节或批量字节接收远端输出流，遇换行符（{@code \n}、{@code \r\n}）立即触发回调消费，
 * 绝不在内存中全量缓冲命令输出，从根本上杜绝长耗时任务与高吞吐日志场景下的内存溢出（OOM）风险。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class LineStreamingOutputStream extends OutputStream {

    private final Consumer<String> lineConsumer;
    private final Charset charset;
    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);

    public LineStreamingOutputStream(Consumer<String> lineConsumer, Charset charset) {
        this.lineConsumer = lineConsumer;
        this.charset = charset != null ? charset : StandardCharsets.UTF_8;
    }

    @Override
    public synchronized void write(int b) {
        if (b == '\n') {
            flushLine();
        } else if (b != '\r') {
            lineBuffer.write(b);
        }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        if (b == null) {
            return;
        }
        int start = off;
        int end = off + len;
        for (int i = off; i < end; i++) {
            if (b[i] == '\n') {
                lineBuffer.write(b, start, i - start);
                flushLine();
                start = i + 1;
            } else if (b[i] == '\r') {
                lineBuffer.write(b, start, i - start);
                start = i + 1;
            }
        }
        if (start < end) {
            lineBuffer.write(b, start, end - start);
        }
    }

    @Override
    public synchronized void flush() {
        if (lineBuffer.size() > 0) {
            flushLine();
        }
    }

    @Override
    public synchronized void close() {
        flush();
    }

    private void flushLine() {
        if (lineConsumer != null) {
            String line = lineBuffer.toString(charset);
            lineConsumer.accept(line);
        }
        lineBuffer.reset();
    }
}
