package io.github.atengk.boot.notification.adapter;

import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.ImageMessage;
import io.github.atengk.boot.notification.model.LinkMessage;
import io.github.atengk.boot.notification.model.MarkdownMessage;
import io.github.atengk.boot.notification.model.NotificationMessage;
import io.github.atengk.boot.notification.model.RawMessage;
import io.github.atengk.boot.notification.model.TextMessage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用自定义 JSON Webhook 报文构建适配器
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class GenericPayloadBuilder implements NotificationPayloadBuilder {

    public static final GenericPayloadBuilder INSTANCE = new GenericPayloadBuilder();

    @Override
    public Map<String, Object> buildPayload(NotificationMessage message) {
        if (message == null) {
            return Map.of();
        }
        message.validate();

        Map<String, Object> root = new LinkedHashMap<>();

        if (message instanceof RawMessage raw) {
            return raw.toPayloadMap();
        }

        root.put("msgType", message.getMessageType().getCode());
        root.put("timestamp", Instant.now().toEpochMilli());

        if (message.getChannel() != null) {
            root.put("channel", message.getChannel());
        }

        if (message.getMention() != null && message.getMention().hasMentions()) {
            Map<String, Object> mentionNode = new LinkedHashMap<>();
            mentionNode.put("isAtAll", message.getMention().isAtAll());
            mentionNode.put("atMobiles", message.getMention().getAtMobiles());
            mentionNode.put("atUserIds", message.getMention().getAtUserIds());
            root.put("mention", mentionNode);
        }

        if (message instanceof TextMessage text) {
            root.put("content", text.getContent());
        } else if (message instanceof MarkdownMessage md) {
            root.put("title", md.getTitle());
            root.put("content", md.getContent());
        } else if (message instanceof CardMessage card) {
            root.put("title", card.getTitle());
            root.put("content", card.getContent());
            root.put("headerColor", card.getHeaderColor().name());
            root.put("btnOrientation", card.getBtnOrientation().name());
            root.put("mainImageUrl", card.getMainImageUrl());
            root.put("buttons", card.getButtons());
            if (card.isSingleAction()) {
                root.put("singleActionText", card.getSingleActionText());
                root.put("singleActionUrl", card.getSingleActionUrl());
            }
        } else if (message instanceof LinkMessage link) {
            root.put("title", link.getTitle());
            root.put("text", link.getText());
            root.put("picUrl", link.getPicUrl());
            root.put("messageUrl", link.getMessageUrl());
        } else if (message instanceof ImageMessage img) {
            Map<String, String> imgNode = new LinkedHashMap<>();
            imgNode.put("base64", img.getBase64());
            imgNode.put("md5", img.getMd5());
            root.put("image", imgNode);
        }

        return root;
    }
}
