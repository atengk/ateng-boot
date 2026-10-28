package io.github.atengk.boot.notification.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 交互卡片操作按钮实体模型
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardButton implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 按钮展示文案
     */
    private String text;

    /**
     * 点击后跳转的完整 URL 地址
     */
    private String url;

    /**
     * 按钮样式类型，默认为 DEFAULT
     */
    @Builder.Default
    private CardButtonType type = CardButtonType.DEFAULT;

    public CardButton(String text, String url) {
        this.text = text;
        this.url = url;
        this.type = CardButtonType.DEFAULT;
    }

    public static CardButton of(String text, String url) {
        return new CardButton(text, url);
    }

    public static CardButton primary(String text, String url) {
        return new CardButton(text, url, CardButtonType.PRIMARY);
    }

    public static CardButton danger(String text, String url) {
        return new CardButton(text, url, CardButtonType.DANGER);
    }
}
