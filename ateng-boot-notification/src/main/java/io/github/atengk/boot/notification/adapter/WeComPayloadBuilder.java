package io.github.atengk.boot.notification.adapter;

import io.github.atengk.boot.notification.model.CardButton;
import io.github.atengk.boot.notification.model.CardColor;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.ImageMessage;
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
 * 企业微信群机器人 Webhook 报文构建适配器
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class WeComPayloadBuilder implements NotificationPayloadBuilder {

    public static final WeComPayloadBuilder INSTANCE = new WeComPayloadBuilder();

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
            case IMAGE -> buildImagePayload(root, (ImageMessage) message);
            case RAW -> buildRawPayload(root, (RawMessage) message);
            default -> throw new UnsupportedOperationException("企业微信群机器人暂不支持消息类型: " + message.getMessageType());
        }

        return root;
    }

    private void buildTextPayload(Map<String, Object> root, TextMessage msg) {
        root.put("msgtype", "text");
        Map<String, Object> textNode = new LinkedHashMap<>();
        textNode.put("content", msg.getContent());

        MentionTarget mention = msg.getMention();
        if (mention != null && mention.hasMentions()) {
            List<String> mentionedList = new ArrayList<>(mention.getAtUserIds());
            if (mention.isAtAll()) {
                mentionedList.add("@all");
            }
            if (!mentionedList.isEmpty()) {
                textNode.put("mentioned_list", mentionedList);
            }
            if (!mention.getAtMobiles().isEmpty()) {
                textNode.put("mentioned_mobile_list", new ArrayList<>(mention.getAtMobiles()));
            }
        }

        root.put("text", textNode);
    }

    private void buildMarkdownPayload(Map<String, Object> root, MarkdownMessage msg) {
        root.put("msgtype", "markdown");
        Map<String, Object> mdNode = new LinkedHashMap<>();

        String sanitized = WeComMarkdownSanitizer.sanitize(msg.getContent());

        // 处理企微 Markdown 中的 @人 标签注入
        MentionTarget mention = msg.getMention();
        if (mention != null && mention.hasMentions()) {
            StringBuilder atBuilder = new StringBuilder();
            if (mention.isAtAll()) {
                atBuilder.append(" <@all>");
            }
            for (String userId : mention.getAtUserIds()) {
                atBuilder.append(" <@").append(userId).append(">");
            }
            if (!atBuilder.isEmpty()) {
                sanitized = sanitized + "\n\n" + atBuilder.toString().trim();
            }
        }

        mdNode.put("content", sanitized);
        root.put("markdown", mdNode);
    }

    private void buildCardPayload(Map<String, Object> root, CardMessage msg) {
        root.put("msgtype", "template_card");
        Map<String, Object> card = new LinkedHashMap<>();

        boolean hasImage = msg.getMainImageUrl() != null && !msg.getMainImageUrl().isBlank();
        card.put("card_type", hasImage ? "news_notice" : "text_notice");

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("desc", "系统通知");
        if (msg.getHeaderColor() != null) {
            int descColor = switch (msg.getHeaderColor()) {
                case RED, CARMINE, ORANGE -> 2;
                case GREEN -> 3;
                case GREY -> 0;
                default -> 1;
            };
            source.put("desc_color", descColor);
        }
        card.put("source", source);

        Map<String, String> mainTitle = new LinkedHashMap<>();
        mainTitle.put("title", msg.getTitle() != null ? msg.getTitle() : "系统通知");
        card.put("main_title", mainTitle);

        if (msg.getContent() != null && !msg.getContent().isBlank()) {
            card.put("sub_title_text", msg.getContent());
        }

        if (hasImage) {
            Map<String, Object> imageNode = new LinkedHashMap<>();
            imageNode.put("url", msg.getMainImageUrl());
            imageNode.put("aspect_ratio", 2.25);
            card.put("card_image", imageNode);
        }

        // 单跳转行动
        if (msg.isSingleAction()) {
            String url = msg.getSingleActionUrl();
            if ((url == null || url.isBlank()) && !msg.getButtons().isEmpty()) {
                url = msg.getButtons().get(0).getUrl();
            }
            if (url != null && !url.isBlank()) {
                Map<String, Object> cardAction = new LinkedHashMap<>();
                cardAction.put("type", 1);
                cardAction.put("url", url);
                card.put("card_action", cardAction);
            }
        }

        // 多按钮列表跳转 (jump_list)
        if (!msg.getButtons().isEmpty()) {
            List<Map<String, Object>> jumpList = new ArrayList<>();
            for (CardButton btn : msg.getButtons()) {
                Map<String, Object> j = new LinkedHashMap<>();
                j.put("type", 1);
                j.put("title", btn.getText());
                j.put("url", btn.getUrl());
                jumpList.add(j);
            }
            card.put("jump_list", jumpList);
        }

        root.put("template_card", card);
    }

    private void buildImagePayload(Map<String, Object> root, ImageMessage msg) {
        root.put("msgtype", "image");
        Map<String, String> imageNode = new LinkedHashMap<>();
        imageNode.put("base64", msg.getBase64());
        imageNode.put("md5", msg.getMd5());
        root.put("image", imageNode);
    }
}
