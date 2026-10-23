package io.github.atengk.boot.sample.notification.dto;

import io.github.atengk.boot.notification.model.CardColor;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 互动卡片通知发信请求传输对象
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class CardMessageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 发送目标渠道标识（可选，不传则回退默认渠道）
     */
    private String channel;

    /**
     * 卡片主标题
     */
    @NotBlank(message = "卡片标题不能为空")
    private String title;

    /**
     * 卡片正文内容（支持基础 Markdown 排版）
     */
    @NotBlank(message = "卡片正文不能为空")
    private String content;

    /**
     * 卡片顶部色条主题枚举
     */
    private CardColor headerColor;

    /**
     * 跳转操作按钮文本
     */
    private String buttonTitle;

    /**
     * 跳转操作按钮目标 URL
     */
    private String buttonUrl;

    /**
     * 指定 @提醒的用户工号或 OpenID 列表
     */
    private List<String> atUserIds;
}
