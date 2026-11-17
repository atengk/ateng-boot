package io.github.atengk.boot.notification.adapter;

import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.model.CardButton;
import io.github.atengk.boot.notification.model.CardButtonOrientation;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.ImageMessage;
import io.github.atengk.boot.notification.model.LinkMessage;
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
            case LINK -> buildLinkPayload(root, (LinkMessage) message);
            case IMAGE -> buildImagePayload(root, (ImageMessage) message);
            case RAW -> buildRawPayload(root, (RawMessage) message);
            default -> throw new UnsupportedOperationException("飞书群机器人暂不支持消息类型: " + message.getMessageType());
        }

        return root;
    }

    private void buildTextPayload(Map<String, Object> root, TextMessage msg) {
        root.put("msg_type", "text");

        String currentText = msg.getContent() != null ? msg.getContent() : "";
        StringBuilder textBuilder = new StringBuilder(currentText);
        MentionTarget mention = msg.getMention();
        if (mention != null && mention.hasMentions()) {
            if (mention.isAtAll() && !currentText.contains("<at user_id=\"all\">")) {
                textBuilder.append(" <at user_id=\"all\">所有人</at>");
            }
            for (String userId : mention.getAtUserIds()) {
                String tag = "<at user_id=\"" + userId + "\">";
                if (!currentText.contains(tag)) {
                    textBuilder.append(" ").append(tag).append(userId).append("</at>");
                }
            }
            for (String mobile : mention.getAtMobiles()) {
                if (!currentText.contains("@" + mobile)) {
                    textBuilder.append(" @").append(mobile);
                }
            }
        }

        Map<String, String> contentNode = new LinkedHashMap<>();
        contentNode.put("text", textBuilder.toString());
        root.put("content", contentNode);
    }

    private void buildMarkdownPayload(Map<String, Object> root, MarkdownMessage msg) {
        root.put("msg_type", "interactive");

        Map<String, Object> card = createInteractiveCard(
                msg.getTitle() != null ? msg.getTitle() : "系统通知",
                "blue"
        );

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

        Map<String, Object> card = createInteractiveCard(
                msg.getTitle() != null ? msg.getTitle() : "系统通知",
                msg.getHeaderColor() != null ? msg.getHeaderColor().getFeishuTemplate() : "blue"
        );

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
            if (msg.getBtnOrientation() == CardButtonOrientation.HORIZONTAL && effectiveButtons.size() > 1) {
                actionElement.put("layout", "bisected");
            }

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

    private void buildLinkPayload(Map<String, Object> root, LinkMessage msg) {
        root.put("msg_type", "interactive");

        Map<String, Object> card = createInteractiveCard(
                msg.getTitle() != null ? msg.getTitle() : "图文链接",
                "blue"
        );

        // Elements
        List<Map<String, Object>> elements = new ArrayList<>();

        Map<String, Object> div = new LinkedHashMap<>();
        div.put("tag", "div");

        StringBuilder contentBuilder = new StringBuilder();
        if (msg.getPicUrl() != null && !msg.getPicUrl().isBlank()) {
            contentBuilder.append("![").append(msg.getTitle()).append("](").append(msg.getPicUrl()).append(")\n\n");
        }
        if (msg.getText() != null && !msg.getText().isBlank()) {
            contentBuilder.append(msg.getText());
        }
        injectMentionTags(contentBuilder, msg.getMention());

        Map<String, String> textObj = new LinkedHashMap<>();
        textObj.put("tag", "lark_md");
        textObj.put("content", contentBuilder.toString());
        div.put("text", textObj);
        elements.add(div);

        if (msg.getMessageUrl() != null && !msg.getMessageUrl().isBlank()) {
            Map<String, Object> actionElement = new LinkedHashMap<>();
            actionElement.put("tag", "action");

            Map<String, Object> buttonObj = new LinkedHashMap<>();
            buttonObj.put("tag", "button");

            Map<String, String> btnText = new LinkedHashMap<>();
            btnText.put("tag", "plain_text");
            btnText.put("content", "查看详情");
            buttonObj.put("text", btnText);
            buttonObj.put("type", "primary");
            buttonObj.put("url", msg.getMessageUrl());

            actionElement.put("actions", List.of(buttonObj));
            elements.add(actionElement);
        }

        card.put("elements", elements);
        root.put("card", card);
    }

    private void buildImagePayload(Map<String, Object> root, ImageMessage msg) {
        if (msg.getImageKey() != null && !msg.getImageKey().isBlank()) {
            root.put("msg_type", "image");
            Map<String, String> contentNode = new LinkedHashMap<>();
            contentNode.put("image_key", msg.getImageKey().trim());
            root.put("content", contentNode);
            return;
        }
        if (msg.getImageUrl() != null && !msg.getImageUrl().isBlank()) {
            root.put("msg_type", "interactive");
            Map<String, Object> card = createInteractiveCard("图片通知", "blue");
            List<Map<String, Object>> elements = new ArrayList<>();
            Map<String, Object> div = new LinkedHashMap<>();
            div.put("tag", "div");
            StringBuilder contentBuilder = new StringBuilder("![图片通知](")
                    .append(msg.getImageUrl().trim())
                    .append(")");
            injectMentionTags(contentBuilder, msg.getMention());
            Map<String, String> textObj = new LinkedHashMap<>();
            textObj.put("tag", "lark_md");
            textObj.put("content", contentBuilder.toString());
            div.put("text", textObj);
            elements.add(div);
            card.put("elements", elements);
            root.put("card", card);
            return;
        }
        throw new NotificationException(NotificationResultCode.NOTIFICATION_UNSUPPORTED_PLATFORM,
                "飞书群机器人发送图片请提供 imageKey（原生图片）或公网 imageUrl（图文卡片）");
    }

    private Map<String, Object> createInteractiveCard(String title, String headerTemplate) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true));

        Map<String, Object> header = new LinkedHashMap<>();
        Map<String, String> titleNode = new LinkedHashMap<>();
        titleNode.put("tag", "plain_text");
        titleNode.put("content", title);
        header.put("title", titleNode);
        header.put("template", headerTemplate);
        card.put("header", header);
        return card;
    }

    private void injectMentionTags(StringBuilder builder, MentionTarget mention) {
        if (mention == null || !mention.hasMentions()) {
            return;
        }
        String current = builder.toString();
        StringBuilder appendBuilder = new StringBuilder();

        if (mention.isAtAll() && !current.contains("<at id=\"all\">")) {
            appendBuilder.append("<at id=\"all\"></at> ");
        }
        for (String userId : mention.getAtUserIds()) {
            String tag = "<at id=\"" + userId + "\"></at>";
            if (!current.contains("<at id=\"" + userId + "\"")) {
                appendBuilder.append(tag).append(" ");
            }
        }
        // 飞书开放平台不支持直接以手机号作为 <at> 标签 id，自适应转译为文本标识 @手机号 避免静默丢失
        for (String mobile : mention.getAtMobiles()) {
            if (!current.contains("@" + mobile)) {
                appendBuilder.append("@").append(mobile).append(" ");
            }
        }

        if (!appendBuilder.isEmpty()) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(appendBuilder.toString().trim());
        }
    }
}
