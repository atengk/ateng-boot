package io.github.atengk.boot.document.util;

import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.document.exception.DocumentException;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/**
 * 文档 Web 流式传输内部通用辅助组件
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class DocumentWebHelper {

    private static final String CLASSPATH_PREFIX = "classpath:";

    private DocumentWebHelper() {
    }

    /**
     * 清洗文件名并确保包含指定的文件后缀，严防路径穿越与 Header 注入
     *
     * @param fileName         原始文件名
     * @param defaultExtension 默认后缀（如 .docx 或 .pdf）
     * @return 清洗后的合法文件名
     */
    public static String sanitizeAndFormatFileName(String fileName, String defaultExtension) {
        if (fileName == null) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String noCrLf = fileName.replace("\r", "").replace("\n", "");
        String clean = FileUtil.cleanFilename(noCrLf);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("文件名包含非法路径穿越或无效字符，清洗后为空: " + fileName);
        }
        if (!clean.toLowerCase().endsWith(defaultExtension.toLowerCase())) {
            clean += defaultExtension;
        }
        return clean;
    }

    /**
     * 在显式字节容量上限内有界读取输入流，防止恶意大流引发 OOM
     *
     * @param is       输入流
     * @param maxBytes 允许读取的最大字节上限（<= 0 时退化为单数组安全上限）
     * @return 完整字节内容
     * @throws IOException       读取底层流异常
     * @throws DocumentException 超出最大容量上限时抛出 {@link DocumentResultCode#DOCUMENT_SIZE_EXCEEDED}
     */
    public static byte[] readBytesBounded(InputStream is, long maxBytes) throws IOException {
        Objects.requireNonNull(is, "输入流 is 不能为空");
        long effectiveMax = maxBytes > 0 ? Math.min(maxBytes, Integer.MAX_VALUE - 8L) : Integer.MAX_VALUE - 8L;
        int limit = (int) effectiveMax;
        try {
            return FileUtil.readNBytesSafe(is, limit);
        } catch (IllegalArgumentException e) {
            throw new DocumentException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED,
                    "文档输入流超出最大允许容量上限 (" + effectiveMax + " bytes): " + e.getMessage(), e);
        }
    }

    /**
     * 对输入流包装有界字节计数防卫，读取超过上限立即抛出 {@link DocumentResultCode#DOCUMENT_SIZE_EXCEEDED}
     *
     * @param delegate 原始输入流
     * @param maxBytes 最大允许读取字节数（<= 0 或 {@link Long#MAX_VALUE} 时不拦截）
     * @return 有界包装输入流
     */
    public static InputStream wrapBoundedInputStream(InputStream delegate, long maxBytes) {
        return wrapBoundedInputStream(delegate, maxBytes,
                msg -> new DocumentException(DocumentResultCode.DOCUMENT_SIZE_EXCEEDED, msg));
    }

    /**
     * 对输入流包装有界字节计数防卫，允许调用方自定义超限抛出的异常类型
     *
     * @param delegate                 原始输入流
     * @param maxBytes                 最大允许读取字节数（<= 0 或 {@link Long#MAX_VALUE} 时不拦截）
     * @param overflowExceptionFactory 容量超限异常构造器
     * @return 有界包装输入流
     */
    public static InputStream wrapBoundedInputStream(InputStream delegate, long maxBytes,
                                                     Function<String, ? extends RuntimeException> overflowExceptionFactory) {
        Objects.requireNonNull(delegate, "输入流 delegate 不能为空");
        Objects.requireNonNull(overflowExceptionFactory, "超限异常构造器 overflowExceptionFactory 不能为空");
        if (maxBytes <= 0 || maxBytes == Long.MAX_VALUE) {
            return delegate;
        }
        return new java.io.FilterInputStream(delegate) {
            private long bytesReadTotal = 0;

            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b != -1) {
                    checkOverflow(1);
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) {
                    checkOverflow(n);
                }
                return n;
            }

            private void checkOverflow(int delta) {
                bytesReadTotal += delta;
                if (bytesReadTotal > maxBytes) {
                    throw overflowExceptionFactory.apply(
                            "文档输入流超出最大允许容量上限 (" + maxBytes + " bytes): " + bytesReadTotal);
                }
            }
        };
    }

    /**
     * 自适应解析多类型输入源为 InputStream
     *
     * @param source 输入源对象
     * @return InputStream 输入流
     * @throws IOException 解析或打开流时发生 IO 异常
     */
    public static InputStream resolveInputStream(Object source) throws IOException {
        if (source instanceof InputStream is) {
            return is;
        }
        if (source instanceof byte[] bytes) {
            return new ByteArrayInputStream(bytes);
        }
        if (source instanceof File file) {
            return new FileInputStream(file);
        }
        if (source instanceof Path path) {
            return new FileInputStream(path.toFile());
        }
        if (source instanceof Resource resource) {
            return resource.getInputStream();
        }
        if (source instanceof String path) {
            String trimmed = path.trim();
            if (trimmed.startsWith(CLASSPATH_PREFIX)) {
                String cleanSubPath = FileUtil.cleanPath(trimmed.substring(CLASSPATH_PREFIX.length()));
                String normalized = cleanSubPath.startsWith("/") ? cleanSubPath.substring(1) : cleanSubPath;
                InputStream cis = Thread.currentThread().getContextClassLoader().getResourceAsStream(normalized);
                if (cis != null) {
                    return cis;
                }
                cis = DocumentWebHelper.class.getClassLoader().getResourceAsStream(normalized);
                if (cis != null) {
                    return cis;
                }
                throw new FileNotFoundException("未在类路径下找到指定的文档资源: " + path);
            }
            String cleanPath = FileUtil.cleanPath(trimmed);
            File file = new File(cleanPath);
            if (file.exists() && file.isFile() && file.canRead()) {
                return new FileInputStream(file);
            }
            throw new FileNotFoundException("未定位到指定的文档文件: " + path);
        }
        throw new IllegalArgumentException("不支持的文档输入源类型: " + source.getClass().getName());
    }
}
