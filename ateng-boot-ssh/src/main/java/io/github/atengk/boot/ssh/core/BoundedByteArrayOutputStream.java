package io.github.atengk.boot.ssh.core;

import org.springframework.util.Assert;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 有界内存字节缓冲输出流 (BoundedByteArrayOutputStream)
 * <p>
 * 限制单次同步命令标准输出或标准错误在内存中的最大驻留字节数，防止超大输出引发 JVM 堆内存溢出（OOM）。
 * 当写入字节超出 {@code maxBytes} 上限时，仅保留前缀内容并静默丢弃后续字节（持续消费通道流以防远端进程写阻塞），
 * 同时置位 {@link #isTruncated()} 供上游准确感知截断状态。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class BoundedByteArrayOutputStream extends OutputStream {

    private static final int MAX_BUFFER_CAPACITY = Integer.MAX_VALUE - 8;

    private final long maxBytes;
    private final ByteArrayOutputStream buffer;
    private long writtenBytes;
    private long discardedBytes;
    private boolean truncated;

    /**
     * 构造指定最大字节上限的有界输出缓冲流
     *
     * @param maxBytes 允许在内存中保留的最大字节数（必须大于等于 0）
     */
    public BoundedByteArrayOutputStream(long maxBytes) {
        Assert.isTrue(maxBytes >= 0, "maxBytes must be non-negative: " + maxBytes);
        this.maxBytes = Math.min(maxBytes, MAX_BUFFER_CAPACITY);
        this.buffer = new ByteArrayOutputStream((int) Math.min(1024L, this.maxBytes));
    }

    @Override
    public void write(int b) {
        if (writtenBytes < maxBytes) {
            buffer.write(b);
            writtenBytes++;
        } else {
            truncated = true;
            discardedBytes++;
        }
    }

    @Override
    public void write(byte[] b, int off, int len) {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return;
        }
        long remaining = maxBytes - writtenBytes;
        if (remaining > 0) {
            int toWrite = (int) Math.min(remaining, len);
            buffer.write(b, off, toWrite);
            writtenBytes += toWrite;
            if (len > toWrite) {
                truncated = true;
                discardedBytes += (len - toWrite);
            }
        } else {
            truncated = true;
            discardedBytes += len;
        }
    }

    /**
     * 按指定字符集将已保留的有界字节解码为字符串
     *
     * @param charset 字符集编码（为 null 时回退 UTF-8）
     * @return 解码字符串
     */
    public String toString(Charset charset) {
        Charset effectiveCharset = charset != null ? charset : StandardCharsets.UTF_8;
        return buffer.toString(effectiveCharset);
    }

    /**
     * 获取是否因超出容量上限而发生输出截断
     *
     * @return true 若发生截断
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * 获取已保留写入缓冲区的字节总数
     *
     * @return 已保留字节数
     */
    public long getWrittenBytes() {
        return writtenBytes;
    }

    /**
     * 获取因超限被丢弃的字节总数
     *
     * @return 被丢弃字节数
     */
    public long getDiscardedBytes() {
        return discardedBytes;
    }
}
