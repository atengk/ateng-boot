package io.github.atengk.boot.sample.notification.dto;

import io.github.atengk.boot.notification.model.CardButtonOrientation;
import io.github.atengk.boot.notification.model.CardButtonType;
import io.github.atengk.boot.notification.model.CardColor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
     * 按钮排版方向（默认 VERTICAL 竖向；HORIZONTAL 横向）
     */
    private CardButtonOrientation btnOrientation;

    /**
     * 单行动跳转操作按钮文本（兼容单按钮快速接入）
     */
    private String buttonTitle;

    /**
     * 单行动跳转操作按钮目标 URL（兼容单按钮快速接入）
     */
    private String buttonUrl;

    /**
     * 多操作按钮决策列表（当存在 2 个及以上按钮时，可与 btnOrientation 配合生效横向/竖向排版）
     */
    @Valid
    private List<CardButtonItemRequest> buttons;

    /**
     * 指定 @提醒的用户工号或 OpenID 列表
     */
    private List<String> atUserIds;

    /**
     * 互动卡片独立操作按钮条目请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CardButtonItemRequest implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 按钮标题文本
         */
        @NotBlank(message = "按钮文本不能为空")
        private String title;

        /**
         * 按钮跳转目标完整 URL
         */
        @NotBlank(message = "按钮目标 URL 不能为空")
        private String url;

        /**
         * 按钮视觉样式类型（DEFAULT / PRIMARY / DANGER）
         */
        private CardButtonType type;
    }
}
