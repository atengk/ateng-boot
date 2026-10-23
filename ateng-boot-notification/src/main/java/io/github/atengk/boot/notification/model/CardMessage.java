package io.github.atengk.boot.notification.model;

import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 统一跨平台交互卡片通知领域消息模型
 * 支持自适应转译为钉钉 ActionCard、企微 TemplateCard 与飞书 Interactive Card v2
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class CardMessage extends NotificationMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 卡片主标题
     */
    private String title;

    /**
     * 卡片正文摘要内容（支持 Markdown 语法）
     */
    private String content;

    /**
     * 卡片顶栏主题色，默认蓝色 (BLUE)
     */
    private CardColor headerColor = CardColor.BLUE;

    /**
     * 卡片封面主图完整 URL（可选）
     */
    private String mainImageUrl;

    /**
     * 交互操作按钮列表
     */
    private List<CardButton> buttons = new ArrayList<>();

    /**
     * 单行动跳转文案（可选）
     */
    private String singleActionText;

    /**
     * 单行动跳转完整 URL（可选）
     */
    private String singleActionUrl;

    public CardMessage(String title, String content) {
        this.title = title;
        this.content = content;
    }

    public static CardMessage of(String title, String content) {
        return new CardMessage(title, content);
    }

    public CardMessage title(String title) {
        this.title = title;
        return this;
    }

    public CardMessage content(String content) {
        this.content = content;
        return this;
    }

    public CardMessage headerColor(CardColor headerColor) {
        this.headerColor = headerColor != null ? headerColor : CardColor.BLUE;
        return this;
    }

    public CardMessage mainImageUrl(String mainImageUrl) {
        this.mainImageUrl = mainImageUrl;
        return this;
    }

    public CardMessage button(CardButton button) {
        if (button != null) {
            this.buttons.add(button);
        }
        return this;
    }

    public CardMessage button(String text, String url) {
        return button(new CardButton(text, url));
    }

    public CardMessage button(String text, String url, CardButtonType type) {
        return button(new CardButton(text, url, type));
    }

    public CardMessage buttons(Collection<CardButton> buttons) {
        if (buttons != null) {
            this.buttons.addAll(buttons);
        }
        return this;
    }

    public CardMessage singleAction(String text, String url) {
        this.singleActionText = text;
        this.singleActionUrl = url;
        return this;
    }

    /**
     * 判断当前卡片是否按单按钮/整体跳转呈现
     */
    public boolean isSingleAction() {
        if (singleActionUrl != null && !singleActionUrl.isBlank()) {
            return true;
        }
        return buttons != null && buttons.size() == 1;
    }

    public List<CardButton> getButtons() {
        return buttons != null ? buttons : Collections.emptyList();
    }

    @Override
    public MessageType getMessageType() {
        return MessageType.CARD;
    }

    @Override
    public void validate() {
        boolean hasTitle = title != null && !title.isBlank();
        boolean hasContent = content != null && !content.isBlank();
        if (!hasTitle && !hasContent) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_MESSAGE_EMPTY, "交互卡片标题或正文内容至少一项不能为空");
        }
    }
}
