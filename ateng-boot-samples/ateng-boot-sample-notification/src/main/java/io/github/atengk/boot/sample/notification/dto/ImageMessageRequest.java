package io.github.atengk.boot.sample.notification.dto;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 图片通知发信请求传输对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
public class ImageMessageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 发送目标渠道标识（可选，不传则回退默认渠道）
     */
    private String channel;

    /**
     * 图片 Base64 编码字符串（与 imagePath 二选一）
     */
    private String base64;

    /**
     * 图片 MD5 校验和（配合 base64 传入）
     */
    private String md5;

    /**
     * 本地图片文件绝对路径（与 base64 二选一，自动计算 Base64 与 MD5 并执行零字节防卫）
     */
    private String imagePath;

    /**
     * 校验请求载荷是否至少提供 Base64 或本地图片路径
     *
     * @return 校验通过返回 true
     */
    @AssertTrue(message = "必须提供有效图片 Base64 内容或本地图片路径")
    public boolean isValidPayload() {
        return (base64 != null && !base64.isBlank()) || (imagePath != null && !imagePath.isBlank());
    }
}
