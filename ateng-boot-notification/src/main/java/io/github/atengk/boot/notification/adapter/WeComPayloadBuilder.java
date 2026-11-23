package io.github.atengk.boot.notification.adapter;

import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.model.CardButton;
import io.github.atengk.boot.notification.model.CardColor;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.ImageMessage;
import io.github.atengk.boot.notification.model.LinkMessage;
import io.github.atengk.boot.notification.model.MarkdownMessage;
import io.github.atengk.boot.notification.model.MentionTarget;
import io.github.atengk.boot.notification.model.NotificationMessage;
import io.github.atengk.boot.notification.model.RawMessage;
import io.github.atengk.boot.notification.model.TextMessage;
import io.github.atengk.boot.notification.util.NotificationValidationUtil;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class WeComPayloadBuilder implements NotificationPayloadBuilder {

    public static final WeComPayloadBuilder INSTANCE = new WeComPayloadBuilder();
    private static final String DEFAULT_TEMPLATE_CARD_URL = "https://work.weixin.qq.com";
    private static final int MAX_JUMP_LIST_SIZE = 3;

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
            case LINK -> buildLinkPayload(root, (LinkMessage) message);
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
        if (hasImage && msg.getContent() != null && !msg.getContent().isBlank()) {
            mainTitle.put("desc", msg.getContent());
        }
        card.put("main_title", mainTitle);

        if (!hasImage && msg.getContent() != null && !msg.getContent().isBlank()) {
            card.put("sub_title_text", msg.getContent());
        }

        if (hasImage) {
            Map<String, Object> imageNode = new LinkedHashMap<>();
            imageNode.put("url", msg.getMainImageUrl());
            imageNode.put("aspect_ratio", 2.25);
            card.put("card_image", imageNode);
        }

        // 企业微信 template_card 官方规定 card_action 为 100% 必填：按单跳URL -> 首按钮URL -> 默认官方地址三级推导
        String actionUrl = resolveCardActionUrl(msg);
        Map<String, Object> cardAction = new LinkedHashMap<>();
        cardAction.put("type", 1);
        cardAction.put("url", actionUrl);
        card.put("card_action", cardAction);

        // 多按钮列表跳转 (jump_list 官方限制最多 3 项)
        if (!msg.getButtons().isEmpty()) {
            if (msg.getButtons().size() > MAX_JUMP_LIST_SIZE) {
                log.warn("企业微信 template_card jump_list 官方限制最多 {} 项，当前传入 {} 项，超出部分将被截断",
                        MAX_JUMP_LIST_SIZE, msg.getButtons().size());
            }
            List<Map<String, Object>> jumpList = new ArrayList<>();
            int limit = Math.min(MAX_JUMP_LIST_SIZE, msg.getButtons().size());
            for (int i = 0; i < limit; i++) {
                CardButton btn = msg.getButtons().get(i);
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

    private String resolveCardActionUrl(CardMessage msg) {
        if (msg.getSingleActionUrl() != null && !msg.getSingleActionUrl().isBlank()) {
            return msg.getSingleActionUrl();
        }
        if (!msg.getButtons().isEmpty()) {
            String firstUrl = msg.getButtons().get(0).getUrl();
            if (firstUrl != null && !firstUrl.isBlank()) {
                return firstUrl;
            }
        }
        return DEFAULT_TEMPLATE_CARD_URL;
    }

    private void buildImagePayload(Map<String, Object> root, ImageMessage msg) {
        if (msg.getBase64() != null && !msg.getBase64().isBlank()) {
            root.put("msgtype", "image");
            Map<String, String> imageNode = new LinkedHashMap<>();
            String base64 = msg.getBase64().trim();
            String md5 = msg.getMd5();
            if (md5 == null || md5.isBlank()) {
                byte[] decoded = NotificationValidationUtil.decodeBase64(base64);
                md5 = ImageMessage.computeMd5Hex(decoded);
            }
            imageNode.put("base64", base64);
            imageNode.put("md5", md5);
            root.put("image", imageNode);
            return;
        }
        if (msg.getImageUrl() != null && !msg.getImageUrl().isBlank()) {
            root.put("msgtype", "news");
            Map<String, Object> newsNode = new LinkedHashMap<>();
            Map<String, Object> article = new LinkedHashMap<>();
            article.put("title", "图片通知");
            article.put("description", "");
            article.put("url", msg.getImageUrl().trim());
            article.put("picurl", msg.getImageUrl().trim());
            newsNode.put("articles", List.of(article));
            root.put("news", newsNode);
            return;
        }
        throw new NotificationException(NotificationResultCode.NOTIFICATION_UNSUPPORTED_PLATFORM,
                "企业微信群机器人发送图片请提供 Base64 编码或公网 imageUrl");
    }

    private void buildLinkPayload(Map<String, Object> root, LinkMessage msg) {
        root.put("msgtype", "news");
        Map<String, Object> newsNode = new LinkedHashMap<>();
        Map<String, Object> article = new LinkedHashMap<>();
        article.put("title", msg.getTitle());
        article.put("description", msg.getText() != null ? msg.getText() : "");
        article.put("url", msg.getMessageUrl());
        article.put("picurl", msg.getPicUrl() != null ? msg.getPicUrl() : "");
        newsNode.put("articles", List.of(article));
        root.put("news", newsNode);
    }
}
