package io.github.atengk.boot.notification.adapter;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.notification.model.CardButton;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.MarkdownMessage;
import io.github.atengk.boot.notification.model.MentionTarget;
import io.github.atengk.boot.notification.model.NotificationMessage;
import io.github.atengk.boot.notification.model.RawMessage;
import io.github.atengk.boot.notification.model.TextMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞书群机器人 Webhook 报文构建适配器
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class FeishuPayloadBuilder implements NotificationPayloadBuilder {

    public static final FeishuPayloadBuilder INSTANCE = new FeishuPayloadBuilder();

    @Override
    public Map<String, Object> buildPayload(NotificationMessage message) {
        if (message == null) {
            return Map.of();
        }
        message.validate();

        Map<String, Object> root = new LinkedHashMap<>();

        switch (message.getMessageType()) {
            case TEXT -> buildTextPayload(root, (TextMessage) message);
            case MARKDOWN -> buildMarkdownPayload(root, (MarkdownMessage) message);
            case CARD -> buildCardPayload(root, (CardMessage) message);
            case RAW -> buildRawPayload(root, (RawMessage) message);
            default -> throw new UnsupportedOperationException("飞书群机器人暂不支持消息类型: " + message.getMessageType());
        }

        return root;
    }

    private void buildTextPayload(Map<String, Object> root, TextMessage msg) {
        root.put("msg_type", "text");

        StringBuilder textBuilder = new StringBuilder(msg.getContent());
        MentionTarget mention = msg.getMention();
        if (mention != null && mention.hasMentions()) {
            if (mention.isAtAll()) {
                textBuilder.append(" <at user_id=\"all\">所有人</at>");
            }
            for (String userId : mention.getAtUserIds()) {
                textBuilder.append(" <at user_id=\"").append(userId).append("\">").append(userId).append("</at>");
            }
        }

        Map<String, String> contentNode = new LinkedHashMap<>();
        contentNode.put("text", textBuilder.toString());
        root.put("content", contentNode);
    }

    private void buildMarkdownPayload(Map<String, Object> root, MarkdownMessage msg) {
        root.put("msg_type", "interactive");

        Map<String, Object> card = new LinkedHashMap<>();

        // Header
        Map<String, Object> header = new LinkedHashMap<>();
        Map<String, String> titleNode = new LinkedHashMap<>();
        titleNode.put("tag", "plain_text");
        titleNode.put("content", msg.getTitle() != null ? msg.getTitle() : "系统通知");
        header.put("title", titleNode);
        header.put("template", "blue");
        card.put("header", header);

        // Elements
        List<Map<String, Object>> elements = new ArrayList<>();
        Map<String, Object> div = new LinkedHashMap<>();
        div.put("tag", "div");

        StringBuilder mdContent = new StringBuilder(msg.getContent());
        injectMentionTags(mdContent, msg.getMention());

        Map<String, String> textObj = new LinkedHashMap<>();
        textObj.put("tag", "lark_md");
        textObj.put("content", mdContent.toString());
        div.put("text", textObj);
        elements.add(div);

        card.put("elements", elements);
        root.put("card", card);
    }

    private void buildCardPayload(Map<String, Object> root, CardMessage msg) {
        root.put("msg_type", "interactive");

        Map<String, Object> card = new LinkedHashMap<>();

        // Header
        Map<String, Object> header = new LinkedHashMap<>();
        Map<String, String> titleNode = new LinkedHashMap<>();
        titleNode.put("tag", "plain_text");
        titleNode.put("content", msg.getTitle() != null ? msg.getTitle() : "系统通知");
        header.put("title", titleNode);
        header.put("template", msg.getHeaderColor() != null ? msg.getHeaderColor().getFeishuTemplate() : "blue");
        card.put("header", header);

        // Elements
        List<Map<String, Object>> elements = new ArrayList<>();

        // 封面图与正文统一嵌入 lark_md div 元素
        Map<String, Object> div = new LinkedHashMap<>();
        div.put("tag", "div");

        StringBuilder contentBuilder = new StringBuilder();
        if (msg.getMainImageUrl() != null && !msg.getMainImageUrl().isBlank()) {
            contentBuilder.append("![").append(msg.getTitle()).append("](").append(msg.getMainImageUrl()).append(")\n\n");
        }
        if (msg.getContent() != null) {
            contentBuilder.append(msg.getContent());
        }
        injectMentionTags(contentBuilder, msg.getMention());

        Map<String, String> textObj = new LinkedHashMap<>();
        textObj.put("tag", "lark_md");
        textObj.put("content", contentBuilder.toString());
        div.put("text", textObj);
        elements.add(div);

        // 按钮交互组件构建 (action)
        List<CardButton> effectiveButtons = new ArrayList<>(msg.getButtons());
        if (effectiveButtons.isEmpty() && msg.isSingleAction() && msg.getSingleActionUrl() != null) {
            String text = msg.getSingleActionText() != null ? msg.getSingleActionText() : "查看详情";
            effectiveButtons.add(CardButton.primary(text, msg.getSingleActionUrl()));
        }

        if (!effectiveButtons.isEmpty()) {
            Map<String, Object> actionElement = new LinkedHashMap<>();
            actionElement.put("tag", "action");

            List<Map<String, Object>> actions = new ArrayList<>();
            for (CardButton btn : effectiveButtons) {
                Map<String, Object> buttonObj = new LinkedHashMap<>();
                buttonObj.put("tag", "button");

                Map<String, String> btnText = new LinkedHashMap<>();
                btnText.put("tag", "plain_text");
                btnText.put("content", btn.getText());
                buttonObj.put("text", btnText);

                buttonObj.put("type", btn.getType() != null ? btn.getType().getCode() : "default");
                buttonObj.put("url", btn.getUrl());
                actions.add(buttonObj);
            }
            actionElement.put("actions", actions);
            elements.add(actionElement);
        }

        card.put("elements", elements);
        root.put("card", card);
    }

    private void buildRawPayload(Map<String, Object> root, RawMessage msg) {
        if (msg.getRawPayload() != null && !msg.getRawPayload().isEmpty()) {
            root.putAll(msg.getRawPayload());
        } else if (msg.getRawJson() != null && !msg.getRawJson().isBlank()) {
            Map<String, Object> parsed = JsonUtil.parseMap(msg.getRawJson());
            if (parsed != null) {
                root.putAll(parsed);
            }
        }
    }

    private void injectMentionTags(StringBuilder builder, MentionTarget mention) {
        if (mention == null || !mention.hasMentions()) {
            return;
        }
        builder.append("\n\n");
        if (mention.isAtAll()) {
            builder.append("<at id=\"all\"></at> ");
        }
        for (String userId : mention.getAtUserIds()) {
            builder.append("<at id=\"").append(userId).append("\"></at> ");
        }
    }
}
