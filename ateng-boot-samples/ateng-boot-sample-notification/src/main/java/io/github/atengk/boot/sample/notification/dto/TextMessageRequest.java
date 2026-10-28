package io.github.atengk.boot.sample.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 纯文本通知发信请求传输对象
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class TextMessageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 发送目标渠道标识（可选，不传则回退默认渠道）
     */
    private String channel;

    /**
     * 文本消息正文内容
     */
    @NotBlank(message = "文本内容不能为空")
    private String content;

    /**
     * 是否 @所有人
     */
    private Boolean atAll;

    /**
     * 指定 @提醒的手机号列表
     */
    private List<String> atMobiles;

    /**
     * 指定 @提醒的用户工号或 OpenID 列表
     */
    private List<String> atUserIds;
}
