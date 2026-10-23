package io.github.atengk.boot.sample.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 富文本 Markdown 通知发信请求传输对象
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class MarkdownMessageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 发送目标渠道标识（可选，不传则回退默认渠道）
     */
    private String channel;

    /**
     * Markdown 消息摘要标题（用于客户端通知弹窗与列表预览）
     */
    @NotBlank(message = "消息标题不能为空")
    private String title;

    /**
     * Markdown 语法正文内容
     */
    @NotBlank(message = "Markdown 正文不能为空")
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
