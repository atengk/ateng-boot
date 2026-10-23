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
 * 钉钉群机器人 Webhook 报文构建适配器
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class DingTalkPayloadBuilder implements NotificationPayloadBuilder {

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
        mdNode.put("text", msg.getContent());
        root.put("markdown", mdNode);

        attachAt(root, msg.getMention());
    }

    private void buildCardPayload(Map<String, Object> root, CardMessage msg) {
        root.put("msgtype", "actionCard");
        Map<String, Object> cardNode = new LinkedHashMap<>();
        cardNode.put("title", msg.getTitle() != null ? msg.getTitle() : "系统通知");

        // 正文处理（主图拼装在 Markdown 头部）
        StringBuilder textBuilder = new StringBuilder();
        if (msg.getMainImageUrl() != null && !msg.getMainImageUrl().isBlank()) {
            textBuilder.append("![").append(msg.getTitle()).append("](").append(msg.getMainImageUrl()).append(")\n\n");
        }
        if (msg.getContent() != null) {
            textBuilder.append(msg.getContent());
        }
        cardNode.put("text", textBuilder.toString());

        // 按钮构建
        if (msg.isSingleAction()) {
            String singleTitle = msg.getSingleActionText();
            String singleUrl = msg.getSingleActionUrl();
            if ((singleTitle == null || singleTitle.isBlank()) && !msg.getButtons().isEmpty()) {
                CardButton firstBtn = msg.getButtons().get(0);
                singleTitle = firstBtn.getText();
                singleUrl = firstBtn.getUrl();
            }
            cardNode.put("singleTitle", singleTitle != null ? singleTitle : "查看详情");
            cardNode.put("singleURL", singleUrl != null ? singleUrl : "");
        } else {
            cardNode.put("btnOrientation", "0"); // 默认竖直排列
            List<Map<String, String>> btnList = new ArrayList<>();
            for (CardButton btn : msg.getButtons()) {
                Map<String, String> b = new LinkedHashMap<>();
                b.put("title", btn.getText());
                b.put("actionURL", btn.getUrl());
                btnList.add(b);
            }
            cardNode.put("btns", btnList);
        }

        root.put("actionCard", cardNode);
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
