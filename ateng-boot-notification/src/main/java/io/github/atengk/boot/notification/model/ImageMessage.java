package io.github.atengk.boot.notification.model;

import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 图片通知领域消息模型
 * 结构化承载 Base64 编码与 MD5 校验和（主要用于企微等原生图片推送）
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
     * 图片 Base64 编码字符串
     */
    private String base64;

    /**
     * 图片二进制内容 MD5 哈希校验和
     */
    private String md5;

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
     * 基于原始图片二进制字节数组自动计算 Base64 与 MD5
     *
     * @param imageBytes 图片字节流
     * @return 初始化的 ImageMessage 实例
     */
    public static ImageMessage fromBytes(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY, "图片二进制字节流不能为空");
        }
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        try {
            MessageDigest md5Digest = MessageDigest.getInstance("MD5");
            byte[] digest = md5Digest.digest(imageBytes);
            String md5Hex = HexFormat.of().formatHex(digest);
            return new ImageMessage(b64, md5Hex);
        } catch (NoSuchAlgorithmException ex) {
            throw new NotificationException("系统缺少 MD5 摘要算法支持", ex);
        }
    }

    public ImageMessage base64(String base64) {
        this.base64 = base64;
        return this;
    }

    public ImageMessage md5(String md5) {
        this.md5 = md5;
        return this;
    }

    @Override
    public MessageType getMessageType() {
        return MessageType.IMAGE;
    }

    @Override
    public void validate() {
        if (base64 == null || base64.isBlank()) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY, "图片消息 Base64 内容不能为空");
        }
    }
}
