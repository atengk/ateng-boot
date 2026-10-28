package io.github.atengk.boot.document.util;

import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

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
     * 清洗文件名并确保包含指定的文件后缀
     *
     * @param fileName         原始文件名
     * @param defaultExtension 默认后缀（如 .docx 或 .pdf）
     * @return 清洗后的合法文件名
     */
    public static String sanitizeAndFormatFileName(String fileName, String defaultExtension) {
        String clean = fileName.replace("\r", "").replace("\n", "").trim();
        if (!clean.toLowerCase().endsWith(defaultExtension.toLowerCase())) {
            clean += defaultExtension;
        }
        return clean;
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
            if (path.startsWith(CLASSPATH_PREFIX)) {
                String subPath = path.substring(CLASSPATH_PREFIX.length()).trim();
                String normalized = subPath.startsWith("/") ? subPath.substring(1) : subPath;
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
            File file = new File(path);
            if (file.exists() && file.isFile() && file.canRead()) {
                return new FileInputStream(file);
            }
            throw new FileNotFoundException("未定位到指定的文档文件: " + path);
        }
        throw new IllegalArgumentException("不支持的文档输入源类型: " + source.getClass().getName());
    }
}
