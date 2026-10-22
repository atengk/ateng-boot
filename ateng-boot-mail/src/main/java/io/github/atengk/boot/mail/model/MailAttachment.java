package io.github.atengk.boot.mail.model;

import jakarta.activation.FileTypeMap;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamSource;
import org.springframework.core.io.Resource;

import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.io.Serializable;
import java.util.Objects;

/**
 * 邮件流式附件领域模型
 * <p>
 * 封装邮件独立附件载体，支持基于 {@link File}、{@link InputStreamSource} 与 Spring 原生 {@link Resource} 的流式装配，
 * 内置 RFC 6266 文件名安全清洗、MIME 类型推导与确定性生命周期清理机制。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailAttachment implements AutoCloseable, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 清洗后的附件文件名（遵循 RFC 6266 与安全防路径穿越规范）
     */
    private String filename;

    /**
     * 流式数据源
     */
    private transient InputStreamSource source;

    /**
     * 附件 MIME 内容类型（如 application/pdf、application/vnd.openxmlformats-officedocument.spreadsheetml.sheet）
     */
    private String contentType;

    /**
     * 已知附件容量（字节数，若未知可为 null）
     */
    private Long size;

    /**
     * 确定性资源释放回调
     */
    private transient Runnable cleanupCallback;

    /**
     * 基于本地磁盘文件构建附件
     *
     * @param filename 附件显示名称（若为空则默认取文件自身名称）
     * @param file     本地文件对象
     * @return 邮件附件模型
     */
    public static MailAttachment of(String filename, File file) {
        Objects.requireNonNull(file, "file must not be null");
        String finalName = (filename != null && !filename.isBlank()) ? filename : file.getName();
        String cleaned = cleanFilename(finalName);
        String mime = resolveContentType(cleaned, null);
        return MailAttachment.builder()
                .filename(cleaned)
                .source(new FileSystemResource(file))
                .contentType(mime)
                .size(file.length())
                .build();
    }

    /**
     * 基于本地磁盘文件构建附件（使用文件原生名称）
     *
     * @param file 本地文件对象
     * @return 邮件附件模型
     */
    public static MailAttachment of(File file) {
        return of(null, file);
    }

    /**
     * 基于字节数组构建附件
     *
     * @param filename 附件显示名称
     * @param bytes    字节数据
     * @return 邮件附件模型
     */
    public static MailAttachment of(String filename, byte[] bytes) {
        return of(filename, bytes, null);
    }

    /**
     * 基于字节数组与指定 MIME 类型构建附件
     *
     * @param filename    附件显示名称
     * @param bytes       字节数据
     * @param contentType 附件 MIME 类型
     * @return 邮件附件模型
     */
    public static MailAttachment of(String filename, byte[] bytes, String contentType) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        String cleaned = cleanFilename(filename);
        String mime = resolveContentType(cleaned, contentType);
        return MailAttachment.builder()
                .filename(cleaned)
                .source(new ByteArrayResource(bytes))
                .contentType(mime)
                .size((long) bytes.length)
                .build();
    }

    /**
     * 基于流式数据源构建附件
     *
     * @param filename 附件显示名称
     * @param source   流式数据源
     * @return 邮件附件模型
     */
    public static MailAttachment of(String filename, InputStreamSource source) {
        return of(filename, source, null);
    }

    /**
     * 基于流式数据源与指定 MIME 类型构建附件
     *
     * @param filename    附件显示名称
     * @param source      流式数据源
     * @param contentType 附件 MIME 类型
     * @return 邮件附件模型
     */
    public static MailAttachment of(String filename, InputStreamSource source, String contentType) {
        Objects.requireNonNull(source, "source must not be null");
        String cleaned = cleanFilename(filename);
        String mime = resolveContentType(cleaned, contentType);
        Long detectedSize = null;
        if (source instanceof Resource resource) {
            try {
                long len = resource.contentLength();
                if (len >= 0) {
                    detectedSize = len;
                }
            } catch (Exception ignored) {
                // 某些不可重复读流获取长度可能抛异常，安全降级
            }
        }
        return MailAttachment.builder()
                .filename(cleaned)
                .source(source)
                .contentType(mime)
                .size(detectedSize)
                .build();
    }

    /**
     * 基于 Spring 原生 Resource 构建附件
     *
     * @param filename 附件显示名称（若为空取 resource.getFilename()）
     * @param resource 资源对象
     * @return 邮件附件模型
     */
    public static MailAttachment of(String filename, Resource resource) {
        Objects.requireNonNull(resource, "resource must not be null");
        String finalName = (filename != null && !filename.isBlank()) ? filename : resource.getFilename();
        return of(finalName, (InputStreamSource) resource, null);
    }

    /**
     * 基于原始 InputStream 构建附件（发信结束后自动安全关闭该流）
     *
     * @param filename    附件显示名称
     * @param inputStream 输入流
     * @return 邮件附件模型
     */
    public static MailAttachment of(String filename, InputStream inputStream) {
        return of(filename, inputStream, null);
    }

    /**
     * 基于原始 InputStream 与指定 MIME 类型构建附件
     *
     * @param filename    附件显示名称
     * @param inputStream 输入流
     * @param contentType MIME 类型
     * @return 邮件附件模型
     */
    public static MailAttachment of(String filename, InputStream inputStream, String contentType) {
        Objects.requireNonNull(inputStream, "inputStream must not be null");
        String cleaned = cleanFilename(filename);
        String mime = resolveContentType(cleaned, contentType);
        return MailAttachment.builder()
                .filename(cleaned)
                .source(() -> inputStream)
                .contentType(mime)
                .cleanupCallback(() -> {
                    try {
                        inputStream.close();
                    } catch (Exception ignored) {
                    }
                })
                .build();
    }

    /**
     * 注册确定性资源清理回调钩子
     *
     * @param cleanupCallback 清理回调任务
     * @return 当前附件对象
     */
    public MailAttachment onCleanup(Runnable cleanupCallback) {
        this.cleanupCallback = cleanupCallback;
        return this;
    }

    /**
     * 执行确定性资源清理，释放底层句柄
     */
    @Override
    public void close() {
        if (cleanupCallback != null) {
            try {
                cleanupCallback.run();
            } catch (Exception ignored) {
            }
        }
        if (source instanceof AutoCloseable autoCloseable) {
            try {
                autoCloseable.close();
            } catch (Exception ignored) {
            }
        } else if (source instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 规范化并清洗附件文件名，防范跨目录遍历与非法控制字符
     *
     * @param raw 原始文件名
     * @return 安全文件名
     */
    public static String cleanFilename(String raw) {
        if (raw == null || raw.isBlank()) {
            return "attachment.dat";
        }
        // 宿主中立：清洗反斜杠为正斜杠，截取最后一个路径分隔符之后的部分
        String name = raw.replace('\\', '/');
        int slashIdx = name.lastIndexOf('/');
        if (slashIdx >= 0) {
            name = name.substring(slashIdx + 1);
        }
        // 去除控制字符与前后空格
        name = name.replaceAll("[\\r\\n\\t]", "").trim();
        // 防御目录穿越
        if (name.equals(".") || name.equals("..") || name.isBlank()) {
            return "attachment.dat";
        }
        return name;
    }

    /**
     * 智能推导 MIME 类型
     *
     * @param filename    文件名
     * @param explicitType 显式指定的类型（可选）
     * @return 解析后的 MIME 类型
     */
    public static String resolveContentType(String filename, String explicitType) {
        if (explicitType != null && !explicitType.isBlank()) {
            return explicitType;
        }
        if (filename != null && !filename.isBlank()) {
            try {
                String probed = java.nio.file.Files.probeContentType(java.nio.file.Path.of(filename));
                if (probed != null && !probed.isBlank()) {
                    return probed;
                }
            } catch (Exception ignored) {
            }
            try {
                String mime = FileTypeMap.getDefaultFileTypeMap().getContentType(filename);
                if (mime != null && !mime.isBlank() && !"application/octet-stream".equalsIgnoreCase(mime)) {
                    return mime;
                }
            } catch (Exception ignored) {
            }
            int dotIdx = filename.lastIndexOf('.');
            if (dotIdx >= 0 && dotIdx < filename.length() - 1) {
                String ext = filename.substring(dotIdx + 1).toLowerCase();
                return switch (ext) {
                    case "pdf" -> "application/pdf";
                    case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                    case "xls" -> "application/vnd.ms-excel";
                    case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    case "doc" -> "application/msword";
                    case "zip" -> "application/zip";
                    case "png" -> "image/png";
                    case "jpg", "jpeg" -> "image/jpeg";
                    case "gif" -> "image/gif";
                    case "txt" -> "text/plain";
                    case "json" -> "application/json";
                    case "xml" -> "application/xml";
                    case "csv" -> "text/csv";
                    case "html", "htm" -> "text/html";
                    default -> "application/octet-stream";
                };
            }
        }
        return "application/octet-stream";
    }
}
