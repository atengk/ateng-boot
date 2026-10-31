package io.github.atengk.boot.sample.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 图文链接通知发信请求传输对象
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Data
public class LinkMessageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标渠道名称（可选，不传则回退默认渠道）
     */
    private String channel;

    /**
     * 链接消息主标题
     */
    @NotBlank(message = "链接标题不能为空")
    private String title;

    /**
     * 链接消息正文摘要或描述
     */
    private String text;

    /**
     * 点击后跳转的完整目标 URL
     */
    @NotBlank(message = "跳转 URL 不能为空")
    private String messageUrl;

    /**
     * 图片封面完整 URL（可选）
     */
    private String picUrl;

    /**
     * 是否 @所有人
     */
    private Boolean atAll;

    /**
     * @提醒的手机号列表
     */
    private List<String> atMobiles;

    /**
     * @提醒的用户工号或 OpenID 列表
     */
    private List<String> atUserIds;
}
