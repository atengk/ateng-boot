package io.github.atengk.boot.notification.model;

import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.util.NotificationValidationUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 图片通知领域消息模型
 * 结构化承载 Base64 编码与 MD5 校验和（企微原生图片）、飞书原生 imageKey 及公网 imageUrl（跨端自适应图文），并内置容量上限防 OOM 守卫
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ImageMessage extends NotificationMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 默认允许读取与发送的最大图片体积上限（2MB，对齐企业微信群机器人官方图片上限）
     */
    public static final DataSize DEFAULT_MAX_IMAGE_SIZE = DataSize.ofMegabytes(2);

    private static final int READ_BUFFER_SIZE = 8192;

    /**
     * 图片 Base64 编码字符串（企业微信 / 通用 Webhook 使用）
     */
    private String base64;

    /**
     * 图片二进制内容 MD5 哈希校验和
     */
    private String md5;

    /**
     * 飞书开放平台原生图片标识（如 img_v2_...，用于飞书群机器人原生 image 消息）
     */
    private String imageKey;

    /**
     * 公网可访问图片完整 URL（用于钉钉/飞书/企微跨端图文自适应发送）
     */
    private String imageUrl;

    public ImageMessage(String base64, String md5) {
        this.base64 = base64;
        this.md5 = md5;
    }

    public static ImageMessage of(String base64, String md5) {
        return new ImageMessage(base64, md5);
    }

    public static ImageMessage ofBase64(String base64, String md5) {
        return of(base64, md5);
    }

    /**
     * 构建基于飞书原生 image_key 的图片消息
     *
     * @param imageKey 飞书开放平台上传图片后获得的 image_key
     * @return 初始化的 ImageMessage 实例
     */
    public static ImageMessage ofFeishuKey(String imageKey) {
        ImageMessage msg = new ImageMessage();
        msg.setImageKey(imageKey);
        return msg;
    }

    /**
     * 构建基于公网图片 URL 的跨端图片消息
     *
     * @param imageUrl 公网可访问的 HTTP/HTTPS 图片链接
     * @return 初始化的 ImageMessage 实例
     */
    public static ImageMessage ofUrl(String imageUrl) {
        ImageMessage msg = new ImageMessage();
        msg.setImageUrl(imageUrl);
        return msg;
    }

    /**
     * 基于原始图片二进制字节数组自动计算 Base64 与 MD5（受默认 2MB 上限防卫）
     *
     * @param imageBytes 图片字节流
     * @return 初始化的 ImageMessage 实例
     */
    public static ImageMessage fromBytes(byte[] imageBytes) {
        return fromBytes(imageBytes, DEFAULT_MAX_IMAGE_SIZE);
    }

    /**
     * 基于原始图片二进制字节数组自动计算 Base64 与 MD5（受指定容量上限防卫）
     *
     * @param imageBytes   图片字节流
     * @param maxImageSize 最大允许图片体积（为空时使用默认 2MB）
     * @return 初始化的 ImageMessage 实例
     */
    public static ImageMessage fromBytes(byte[] imageBytes, DataSize maxImageSize) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY, "图片二进制字节流不能为空");
        }
        long maxBytes = resolveMaxBytes(maxImageSize);
        if (imageBytes.length > maxBytes) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_PARAMETER_INVALID,
                    "图片大小超出允许上限: " + imageBytes.length + " bytes > " + maxBytes + " bytes");
        }
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String md5Hex = computeMd5Hex(imageBytes);
        return new ImageMessage(b64, md5Hex);
    }

    /**
     * 基于输入流读取图片并自动计算 Base64 与 MD5（受默认 2MB 上限防卫）
     *
     * @param inputStream 图片输入流
     * @return 初始化的 ImageMessage 实例
     */
    public static ImageMessage fromInputStream(InputStream inputStream) {
        return fromInputStream(inputStream, DEFAULT_MAX_IMAGE_SIZE);
    }

    /**
     * 基于输入流受限读取图片并自动计算 Base64 与 MD5，防止超大流导致 OOM
     *
     * @param inputStream  图片输入流
     * @param maxImageSize 最大允许图片体积
     * @return 初始化的 ImageMessage 实例
     */
    public static ImageMessage fromInputStream(InputStream inputStream, DataSize maxImageSize) {
        if (inputStream == null) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY, "图片输入流不能为空");
        }
        long maxBytes = resolveMaxBytes(maxImageSize);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            long totalRead = 0L;
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                totalRead += bytesRead;
                if (totalRead > maxBytes) {
                    throw new NotificationException(NotificationResultCode.NOTIFICATION_PARAMETER_INVALID,
                            "图片输入流大小超出允许上限: > " + maxBytes + " bytes");
                }
                out.write(buffer, 0, bytesRead);
            }
            return fromBytes(out.toByteArray(), maxImageSize);
        } catch (IOException ex) {
            throw new NotificationException("读取图片输入流失败", ex);
        }
    }

    /**
     * 基于输入流读取图片并自动计算 Base64 与 MD5（fromInputStream 的语义简写）
     *
     * @param inputStream 图片输入流
     * @return 初始化的 ImageMessage 实例
     */
    public static ImageMessage fromStream(InputStream inputStream) {
        return fromInputStream(inputStream);
    }

    /**
     * 基于输入流受限读取图片并自动计算 Base64 与 MD5（fromInputStream 的语义简写）
     *
     * @param inputStream  图片输入流
     * @param maxImageSize 最大允许图片体积
     * @return 初始化的 ImageMessage 实例
     */
    public static ImageMessage fromStream(InputStream inputStream, DataSize maxImageSize) {
        return fromInputStream(inputStream, maxImageSize);
    }

    /**
     * 基于本地图片文件读取并自动计算 Base64 与 MD5（受默认 2MB 上限防卫）
     *
     * @param file 图片文件
     * @return 初始化的 ImageMessage 实例
     */
    public static ImageMessage fromFile(File file) {
        return fromFile(file, DEFAULT_MAX_IMAGE_SIZE);
    }

    /**
     * 基于本地图片文件读取并自动计算 Base64 与 MD5（受指定容量上限防卫）
     *
     * @param file         图片文件
     * @param maxImageSize 最大允许图片体积
     * @return 初始化的 ImageMessage 实例
     */
    public static ImageMessage fromFile(File file, DataSize maxImageSize) {
        if (file == null) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY, "图片文件不能为空");
        }
        return fromPath(file.toPath(), maxImageSize);
    }

    /**
     * 基于本地图片路径读取并自动计算 Base64 与 MD5（受默认 2MB 上限防卫）
     *
     * @param path 图片文件路径
     * @return 初始化的 ImageMessage 实例
     */
    public static ImageMessage fromPath(Path path) {
        return fromPath(path, DEFAULT_MAX_IMAGE_SIZE);
    }

    /**
     * 基于本地图片路径读取并自动计算 Base64 与 MD5（先验文件长度防 OOM）
     *
     * @param path         图片文件路径
     * @param maxImageSize 最大允许图片体积
     * @return 初始化的 ImageMessage 实例
     */
    public static ImageMessage fromPath(Path path, DataSize maxImageSize) {
        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY, "图片路径不存在或无效: " + path);
        }
        long maxBytes = resolveMaxBytes(maxImageSize);
        try {
            long fileSize = Files.size(path);
            if (fileSize <= 0L) {
                throw new NotificationException(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY, "图片文件内容不能为空: " + path);
            }
            if (fileSize > maxBytes) {
                throw new NotificationException(NotificationResultCode.NOTIFICATION_PARAMETER_INVALID,
                        "图片文件大小超出允许上限: " + fileSize + " bytes > " + maxBytes + " bytes");
            }
            byte[] bytes = Files.readAllBytes(path);
            return fromBytes(bytes, maxImageSize);
        } catch (IOException ex) {
            throw new NotificationException("读取图片路径失败: " + path, ex);
        }
    }

    /**
     * 计算字节数组的小写 32 位 MD5 十六进制摘要
     *
     * @param imageBytes 原始图片字节流
     * @return 小写 MD5 十六进制字符串
     */
    public static String computeMd5Hex(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY, "计算 MD5 摘要的字节流不能为空");
        }
        try {
            MessageDigest md5Digest = MessageDigest.getInstance("MD5");
            byte[] digest = md5Digest.digest(imageBytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new NotificationException("系统缺少 MD5 摘要算法支持", ex);
        }
    }

    private static long resolveMaxBytes(DataSize maxImageSize) {
        return NotificationValidationUtil.resolvePositiveMaxBytes(
                maxImageSize, DEFAULT_MAX_IMAGE_SIZE, "最大图片体积限制");
    }

    public ImageMessage base64(String base64) {
        this.base64 = base64;
        return this;
    }

    public ImageMessage md5(String md5) {
        this.md5 = md5;
        return this;
    }

    public ImageMessage imageKey(String imageKey) {
        this.imageKey = imageKey;
        return this;
    }

    public ImageMessage imageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
        return this;
    }

    @Override
    public MessageType getMessageType() {
        return MessageType.IMAGE;
    }

    @Override
    public void validate() {
        boolean hasBase64 = base64 != null && !base64.isBlank();
        boolean hasKey = imageKey != null && !imageKey.isBlank();
        boolean hasUrl = imageUrl != null && !imageUrl.isBlank();
        if (!hasBase64 && !hasKey && !hasUrl) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY,
                    "图片消息 Base64、imageKey 或 imageUrl 至少一项不能为空");
        }
        if (hasUrl) {
            NotificationValidationUtil.validateHttpUrl(imageUrl, "图片 URL");
        }
    }
}
