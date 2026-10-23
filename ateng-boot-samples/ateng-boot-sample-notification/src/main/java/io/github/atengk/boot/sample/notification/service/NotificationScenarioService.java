package io.github.atengk.boot.sample.notification.service;

import io.github.atengk.boot.notification.context.NotificationContextHolder;
import io.github.atengk.boot.notification.model.CardButton;
import io.github.atengk.boot.notification.model.CardColor;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.ImageMessage;
import io.github.atengk.boot.notification.model.MarkdownMessage;
import io.github.atengk.boot.notification.model.MentionTarget;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.notification.model.RawMessage;
import io.github.atengk.boot.notification.model.TextMessage;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import io.github.atengk.boot.sample.notification.dto.CardMessageRequest;
import io.github.atengk.boot.sample.notification.dto.MarkdownMessageRequest;
import io.github.atengk.boot.sample.notification.dto.TextMessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 企业协同通知发信多场景业务服务
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationScenarioService {

    private final NotificationTemplate notificationTemplate;

    /**
     * 同步发送纯文本通知
     */
    public NotificationSendResult sendText(TextMessageRequest request) {
        TextMessage message = new TextMessage(request.getContent());
        MentionTarget mentionTarget = buildMentionTarget(request.getAtAll(), request.getAtMobiles(), request.getAtUserIds());
        if (mentionTarget != null) {
            message.setMention(mentionTarget);
        }
        return notificationTemplate.send(request.getChannel(), message);
    }

    /**
     * 同步发送富文本 Markdown 通知
     */
    public NotificationSendResult sendMarkdown(MarkdownMessageRequest request) {
        MarkdownMessage message = new MarkdownMessage(request.getTitle(), request.getContent());
        MentionTarget mentionTarget = buildMentionTarget(request.getAtAll(), request.getAtMobiles(), request.getAtUserIds());
        if (mentionTarget != null) {
            message.setMention(mentionTarget);
        }
        return notificationTemplate.send(request.getChannel(), message);
    }

    /**
     * 同步发送结构化互动卡片通知
     */
    public NotificationSendResult sendCard(CardMessageRequest request) {
        CardColor color = request.getHeaderColor() != null ? request.getHeaderColor() : CardColor.BLUE;
        CardMessage message = new CardMessage();
        message.setTitle(request.getTitle());
        message.setContent(request.getContent());
        message.setHeaderColor(color);

        if (StringUtils.hasText(request.getButtonTitle()) && StringUtils.hasText(request.getButtonUrl())) {
            message.button(CardButton.of(request.getButtonTitle(), request.getButtonUrl()));
        }

        if (request.getAtUserIds() != null && !request.getAtUserIds().isEmpty()) {
            message.setMention(MentionTarget.ofUserIds(request.getAtUserIds().toArray(new String[0])));
        }

        return notificationTemplate.send(request.getChannel(), message);
    }

    /**
     * 受管异步发信（透传 MDC 上下文与四维快照）
     */
    public CompletableFuture<NotificationSendResult> sendAsync(TextMessageRequest request) {
        TextMessage message = new TextMessage(request.getContent());
        return notificationTemplate.sendAsync(request.getChannel(), message);
    }

    /**
     * 本地事务感知发信（仅在数据库事务成功物理 commit 后真正调用网络外呼，回滚则自动取消）
     */
    @Transactional(rollbackFor = Exception.class)
    public String sendTransactional(TextMessageRequest request, boolean triggerRollback) {
        TextMessage message = new TextMessage(request.getContent());
        notificationTemplate.sendAfterCommit(request.getChannel(), message);

        if (triggerRollback) {
            throw new IllegalStateException("模拟事务发生致命异常触发回滚，防范幽灵通知发送！");
        }
        return "事务提交成功，发信将在物理 commit 后触发";
    }

    /**
     * 动态模板渲染发信
     */
    public NotificationSendResult sendTemplate(String channel, String templateContent, Map<String, Object> variables) {
        return notificationTemplate.sendTemplate(channel, templateContent, variables);
    }

    /**
     * 原生特化 JSON 逃生舱发信
     */
    public NotificationSendResult sendRaw(String channel, Map<String, Object> rawPayload) {
        return notificationTemplate.send(channel, RawMessage.of(rawPayload));
    }

    /**
     * 图片通知发信
     */
    public NotificationSendResult sendImage(String channel, String base64, String md5) {
        return notificationTemplate.send(channel, ImageMessage.ofBase64(base64, md5));
    }

    /**
     * 演示 NotificationContextHolder 线程级动态切源发信
     */
    public NotificationSendResult sendWithSwitchedChannel(String targetChannel, TextMessageRequest request) {
        TextMessage message = new TextMessage(request.getContent());
        try {
            return NotificationContextHolder.callWithChannel(targetChannel, () -> notificationTemplate.send(message));
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("切换通知渠道发信失败: " + e.getMessage(), e);
        }
    }

    private MentionTarget buildMentionTarget(Boolean atAll, List<String> mobiles, List<String> userIds) {
        boolean hasAll = Boolean.TRUE.equals(atAll);
        boolean hasMobiles = mobiles != null && !mobiles.isEmpty();
        boolean hasUsers = userIds != null && !userIds.isEmpty();

        if (!hasAll && !hasMobiles && !hasUsers) {
            return null;
        }

        MentionTarget mention = new MentionTarget(hasAll);
        if (hasMobiles) {
            mention.addMobiles(mobiles);
        }
        if (hasUsers) {
            mention.addUserIds(userIds);
        }
        return mention;
    }
}
