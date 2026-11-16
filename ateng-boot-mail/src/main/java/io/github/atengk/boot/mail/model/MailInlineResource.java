package io.github.atengk.boot.mail.model;

import io.github.atengk.boot.core.util.FileUtil;
import jakarta.activation.FileTypeMap;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.InputStreamSource;
import org.springframework.core.io.Resource;
import org.springframework.util.unit.DataSize;

import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.io.Serializable;
import java.util.Objects;

/**
 * 邮件正文内联嵌入资源领域模型
 * <p>
 * 专职用于富文本 HTML 中通过 {@code <img src="cid:xxx" />} 引用的内嵌图片与静态资源，
 * 强制绑定唯一的 Content-ID，与普通下载附件职责正交隔离。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailInlineResource implements AutoCloseable, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 内联资源唯一标识（CID，如 "companyLogo"，HTML 中通过 cid:companyLogo 引用）
     */
    private String contentId;

    /**
     * 流式数据源载体
     */
    private transient InputStreamSource source;

    /**
     * 资源 MIME 类型（如 image/png、image/jpeg）
     */
    private String contentType;

    /**
     * 已知资源容量（字节数，若未知可为 null）
     */
    private Long size;

    /**
     * 确定性资源释放回调
     */
    private transient Runnable cleanupCallback;

    /**
     * 是否为系统宿主隐性垃圾文件（如 .DS_Store、desktop.ini、thumbs.db 等，发送时自动跳过）
     */
    @Builder.Default
    private boolean systemJunk = false;

    /**
     * 数据源是否可重复读取（File、byte[]、常规 Resource 与 ofBuffered 为 true；原始一次性 InputStream 为 false）
     */
    @Builder.Default
    private boolean repeatable = true;

    /**
     * 基于本地磁盘文件构建内联资源
     *
     * @param contentId 内联资源 Content-ID
     * @param file      本地文件
     * @return 邮件内联资源模型
     */
    public static MailInlineResource of(String contentId, File file) {
        Objects.requireNonNull(file, "file must not be null");
        boolean junk = FileUtil.isSystemJunkFile(file.toPath()) || FileUtil.isSystemJunkFile(file.getName());
        String cleanCid = cleanContentId(contentId);
        String mime = resolveContentType(file.getName(), null);
        return MailInlineResource.builder()
                .contentId(cleanCid)
                .source(new FileSystemResource(file))
                .contentType(mime)
                .size(file.length())
                .systemJunk(junk)
                .build();
    }

    /**
     * 基于字节数组构建内联资源
     *
     * @param contentId   内联资源 Content-ID
     * @param bytes       字节数组
     * @param contentType 资源 MIME 类型（如 image/png）
     * @return 邮件内联资源模型
     */
    public static MailInlineResource of(String contentId, byte[] bytes, String contentType) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        String cleanCid = cleanContentId(contentId);
        String mime = (contentType != null && !contentType.isBlank()) ? contentType : "image/png";
        return MailInlineResource.builder()
                .contentId(cleanCid)
                .source(new ByteArrayResource(bytes))
                .contentType(mime)
                .size((long) bytes.length)
                .build();
    }

    /**
     * 基于流式数据源构建内联资源
     *
     * @param contentId   内联资源 Content-ID
     * @param source      流式数据源
     * @param contentType 资源 MIME 类型
     * @return 邮件内联资源模型
     */
    public static MailInlineResource of(String contentId, InputStreamSource source, String contentType) {
        Objects.requireNonNull(source, "source must not be null");
        String cleanCid = cleanContentId(contentId);
        String mime = (contentType != null && !contentType.isBlank()) ? contentType : "image/png";
        Long detectedSize = null;
        if (source instanceof Resource resource) {
            try {
                long len = resource.contentLength();
                if (len >= 0) {
                    detectedSize = len;
                }
            } catch (Exception ignored) {
            }
        }
        boolean repeatableSource = !(source instanceof InputStreamResource);
        return MailInlineResource.builder()
                .contentId(cleanCid)
                .source(source)
                .contentType(mime)
                .size(detectedSize)
                .repeatable(repeatableSource)
                .build();
    }

    /**
     * 基于 Spring 原生 Resource 构建内联资源
     *
     * @param contentId 内联资源 Content-ID
     * @param resource  资源对象
     * @return 邮件内联资源模型
     */
    public static MailInlineResource of(String contentId, Resource resource) {
        Objects.requireNonNull(resource, "resource must not be null");
        String mime = resolveContentType(resource.getFilename(), null);
        MailInlineResource inline = of(contentId, (InputStreamSource) resource, mime);
        inline.setSystemJunk(FileUtil.isSystemJunkFile(resource.getFilename()));
        return inline;
    }

    /**
     * 基于原始 InputStream 构建内联资源（发信结束后自动安全关闭流）
     *
     * @param contentId   内联资源 Content-ID
     * @param inputStream 输入流
     * @param contentType MIME 类型
     * @return 邮件内联资源模型
     */
    public static MailInlineResource of(String contentId, InputStream inputStream, String contentType) {
        Objects.requireNonNull(inputStream, "inputStream must not be null");
        String cleanCid = cleanContentId(contentId);
        String mime = (contentType != null && !contentType.isBlank()) ? contentType : "image/png";
        return MailInlineResource.builder()
                .contentId(cleanCid)
                .source(() -> inputStream)
                .contentType(mime)
                .repeatable(false)
                .cleanupCallback(() -> {
                    try {
                        inputStream.close();
                    } catch (Exception ignored) {
                    }
                })
                .build();
    }

    /**
     * 将一次性 InputStream 有界缓冲读取为可重读内联资源（默认最大 20MB，读取完成立即关闭原始输入流）
     *
     * @param contentId   内联资源 Content-ID
     * @param inputStream 输入流
     * @param contentType MIME 类型
     * @return 可重读邮件内联资源模型
     */
    public static MailInlineResource ofBuffered(String contentId, InputStream inputStream, String contentType) {
        return ofBuffered(contentId, inputStream, contentType, MailAttachment.DEFAULT_BUFFERED_MAX_BYTES);
    }

    /**
     * 将一次性 InputStream 按指定 DataSize 上限有界缓冲读取为可重读内联资源
     *
     * @param contentId   内联资源 Content-ID
     * @param inputStream 输入流
     * @param contentType MIME 类型
     * @param maxSize     容量上限
     * @return 可重读邮件内联资源模型
     */
    public static MailInlineResource ofBuffered(String contentId, InputStream inputStream, String contentType, DataSize maxSize) {
        Objects.requireNonNull(maxSize, "maxSize must not be null");
        return ofBuffered(contentId, inputStream, contentType, maxSize.toBytes());
    }

    /**
     * 将一次性 InputStream 按指定字节上限有界缓冲读取为可重读内联资源（读取完成或前置校验失败均立即关闭原始输入流）
     *
     * @param contentId   内联资源 Content-ID
     * @param inputStream 输入流
     * @param contentType MIME 类型
     * @param maxBytes    允许读取的最大字节数
     * @return 可重读邮件内联资源模型
     */
    public static MailInlineResource ofBuffered(String contentId, InputStream inputStream, String contentType, long maxBytes) {
        Objects.requireNonNull(inputStream, "inputStream must not be null");
        String cleanCid;
        try {
            cleanCid = cleanContentId(contentId);
        } catch (Throwable t) {
            try {
                inputStream.close();
            } catch (Exception ignored) {
            }
            throw t;
        }
        String mime = (contentType != null && !contentType.isBlank()) ? contentType : "image/png";
        byte[] bytes = MailAttachment.readBufferedBytes(inputStream, maxBytes, cleanCid, "邮件内联资源");
        return MailInlineResource.builder()
                .contentId(cleanCid)
                .source(new ByteArrayResource(bytes))
                .contentType(mime)
                .size((long) bytes.length)
                .repeatable(true)
                .build();
    }

    /**
     * 注册确定性资源清理回调钩子
     *
     * @param cleanupCallback 清理回调任务
     * @return 当前内联资源对象
     */
    public MailInlineResource onCleanup(Runnable cleanupCallback) {
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
     * 清洗 Content-ID，移除外层包裹的 {@code <}、{@code >} 符号与首尾空格
     *
     * @param rawContentId 原始 Content-ID
     * @return 清洗后的合法 Content-ID
     */
    public static String cleanContentId(String rawContentId) {
        if (rawContentId == null) {
            throw new IllegalArgumentException("contentId must not be null or blank");
        }
        String clean = MailMessage.sanitizeHeaderText(rawContentId).trim();
        if (clean.startsWith("<") && clean.endsWith(">") && clean.length() > 2) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        if (clean.isBlank()) {
            throw new IllegalArgumentException("contentId must not be null or blank");
        }
        return clean;
    }

    private static String resolveContentType(String filename, String explicitType) {
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
                    case "png" -> "image/png";
                    case "jpg", "jpeg" -> "image/jpeg";
                    case "gif" -> "image/gif";
                    case "svg" -> "image/svg+xml";
                    case "webp" -> "image/webp";
                    default -> "image/png";
                };
            }
        }
        return "image/png";
    }
}
