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
import java.util.regex.Pattern;

/**
 * 钉钉群机器人 Webhook 报文构建适配器
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class DingTalkPayloadBuilder implements NotificationPayloadBuilder {

    public static final DingTalkPayloadBuilder INSTANCE = new DingTalkPayloadBuilder();
    private static final String DEFAULT_ACTION_CARD_URL = "https://open.dingtalk.com";

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
            default -> throw new UnsupportedOperationException("钉钉群机器人暂不支持消息类型: " + message.getMessageType());
        }

        return root;
    }

    private void buildTextPayload(Map<String, Object> root, TextMessage msg) {
        root.put("msgtype", "text");
        Map<String, Object> textNode = new LinkedHashMap<>();
        textNode.put("content", msg.getContent());
        root.put("text", textNode);

        attachAt(root, msg.getMention());
    }

    private void buildMarkdownPayload(Map<String, Object> root, MarkdownMessage msg) {
        root.put("msgtype", "markdown");
        Map<String, Object> mdNode = new LinkedHashMap<>();
        mdNode.put("title", msg.getTitle() != null ? msg.getTitle() : "系统通知");

        String text = appendMentionText(msg.getContent(), msg.getMention());
        mdNode.put("text", text);
        root.put("markdown", mdNode);

        attachAt(root, msg.getMention());
    }

    private void buildCardPayload(Map<String, Object> root, CardMessage msg) {
        root.put("msgtype", "actionCard");
        Map<String, Object> cardNode = new LinkedHashMap<>();
        cardNode.put("title", msg.getTitle() != null ? msg.getTitle() : "系统通知");

        // 正文处理（主图拼装在 Markdown 头部，同步幂等注入 @ 提醒文本触发客户端高亮）
        StringBuilder textBuilder = new StringBuilder();
        if (msg.getMainImageUrl() != null && !msg.getMainImageUrl().isBlank()) {
            textBuilder.append("![").append(msg.getTitle()).append("](").append(msg.getMainImageUrl()).append(")\n\n");
        }
        if (msg.getContent() != null) {
            textBuilder.append(msg.getContent());
        }
        cardNode.put("text", appendMentionText(textBuilder.toString(), msg.getMention()));

        // 按钮构建
        if (msg.isSingleAction()) {
            String singleTitle = msg.getSingleActionText();
            String singleUrl = msg.getSingleActionUrl();
            if ((singleTitle == null || singleTitle.isBlank()) && !msg.getButtons().isEmpty()) {
                CardButton firstBtn = msg.getButtons().get(0);
                singleTitle = firstBtn.getText();
                singleUrl = firstBtn.getUrl();
            }
            cardNode.put("singleTitle", singleTitle != null && !singleTitle.isBlank() ? singleTitle : "查看详情");
            cardNode.put("singleURL", singleUrl != null && !singleUrl.isBlank() ? singleUrl : DEFAULT_ACTION_CARD_URL);
        } else if (!msg.getButtons().isEmpty()) {
            String orientation = (msg.getBtnOrientation() != null)
                    ? msg.getBtnOrientation().getCode()
                    : CardButtonOrientation.VERTICAL.getCode();
            cardNode.put("btnOrientation", orientation);
            List<Map<String, String>> btnList = new ArrayList<>();
            for (CardButton btn : msg.getButtons()) {
                Map<String, String> b = new LinkedHashMap<>();
                b.put("title", btn.getText());
                b.put("actionURL", btn.getUrl());
                btnList.add(b);
            }
            cardNode.put("btns", btnList);
        } else {
            // 无按钮时钉钉官方 actionCard 要求 singleTitle 与非空 singleURL 均必填
            cardNode.put("singleTitle", "查看详情");
            cardNode.put("singleURL", DEFAULT_ACTION_CARD_URL);
        }

        root.put("actionCard", cardNode);
        attachAt(root, msg.getMention());
    }

    private String appendMentionText(String rawText, MentionTarget mention) {
        String text = rawText != null ? rawText : "";
        if (mention == null || !mention.hasMentions()) {
            return text;
        }
        StringBuilder appendBuilder = new StringBuilder();
        if (mention.isAtAll() && !containsMentionToken(text, "所有人") && !containsMentionToken(text, "all")) {
            appendBuilder.append("@所有人 ");
        }
        for (String mobile : mention.getAtMobiles()) {
            if (!containsMentionToken(text, mobile)) {
                appendBuilder.append("@").append(mobile).append(" ");
            }
        }
        for (String userId : mention.getAtUserIds()) {
            if (!containsMentionToken(text, userId)) {
                appendBuilder.append("@").append(userId).append(" ");
            }
        }
        String toAppend = appendBuilder.toString().trim();
        if (toAppend.isEmpty()) {
            return text;
        }
        return text.isEmpty() ? toAppend : text + "\n\n" + toAppend;
    }

    private boolean containsMentionToken(String text, String token) {
        if (text.isEmpty() || token == null || token.isBlank()) {
            return false;
        }
        return Pattern.compile("(?<![A-Za-z0-9_.@])@" + Pattern.quote(token) + "(?![A-Za-z0-9_])")
                .matcher(text)
                .find();
    }

    private void buildLinkPayload(Map<String, Object> root, LinkMessage msg) {
        root.put("msgtype", "link");
        Map<String, Object> linkNode = new LinkedHashMap<>();
        linkNode.put("title", msg.getTitle());
        linkNode.put("text", msg.getText() != null ? msg.getText() : "");
        linkNode.put("messageUrl", msg.getMessageUrl());
        linkNode.put("picUrl", msg.getPicUrl() != null ? msg.getPicUrl() : "");
        root.put("link", linkNode);

        attachAt(root, msg.getMention());
    }

    private void buildImagePayload(Map<String, Object> root, ImageMessage msg) {
        if (msg.getImageUrl() == null || msg.getImageUrl().isBlank()) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_UNSUPPORTED_PLATFORM,
                    "钉钉群机器人 Webhook 不支持直接发送二进制 Base64 图片，请改用 ImageMessage.ofUrl(imageUrl)");
        }
        root.put("msgtype", "markdown");
        Map<String, Object> mdNode = new LinkedHashMap<>();
        mdNode.put("title", "图片通知");
        String text = appendMentionText("![图片通知](" + msg.getImageUrl().trim() + ")", msg.getMention());
        mdNode.put("text", text);
        root.put("markdown", mdNode);

        attachAt(root, msg.getMention());
    }

    private void attachAt(Map<String, Object> root, MentionTarget mention) {
        if (mention == null || mention.isEmpty()) {
            return;
        }
        Map<String, Object> atNode = new LinkedHashMap<>();
        if (!mention.getAtMobiles().isEmpty()) {
            atNode.put("atMobiles", new ArrayList<>(mention.getAtMobiles()));
        }
        if (!mention.getAtUserIds().isEmpty()) {
            atNode.put("atUserIds", new ArrayList<>(mention.getAtUserIds()));
        }
        atNode.put("isAtAll", mention.isAtAll());
        root.put("at", atNode);
    }
}
