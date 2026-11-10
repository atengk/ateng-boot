package io.github.atengk.boot.sample.notification.service;

import io.github.atengk.boot.notification.context.NotificationContextHolder;
import io.github.atengk.boot.notification.model.CardButton;
import io.github.atengk.boot.notification.model.CardColor;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.ImageMessage;
import io.github.atengk.boot.notification.model.LinkMessage;
import io.github.atengk.boot.notification.model.MarkdownMessage;
import io.github.atengk.boot.notification.model.MentionTarget;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.notification.model.RawMessage;
import io.github.atengk.boot.notification.model.TextMessage;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import io.github.atengk.boot.sample.notification.dto.CardMessageRequest;
import io.github.atengk.boot.sample.notification.dto.ChannelSwitchRequest;
import io.github.atengk.boot.sample.notification.dto.FailoverDemoRequest;
import io.github.atengk.boot.sample.notification.dto.ImageMessageRequest;
import io.github.atengk.boot.sample.notification.dto.LinkMessageRequest;
import io.github.atengk.boot.sample.notification.dto.MarkdownMessageRequest;
import io.github.atengk.boot.sample.notification.dto.RawMessageRequest;
import io.github.atengk.boot.sample.notification.dto.TemplateMessageRequest;
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

        if (request.getBtnOrientation() != null) {
            message.btnOrientation(request.getBtnOrientation());
        }

        // 优先装配多按钮决策列表，其次兼容单按钮字段
        if (request.getButtons() != null && !request.getButtons().isEmpty()) {
            for (CardMessageRequest.CardButtonItemRequest btn : request.getButtons()) {
                if (btn != null && StringUtils.hasText(btn.getTitle()) && StringUtils.hasText(btn.getUrl())) {
                    CardButton cardButton = (btn.getType() != null)
                            ? CardButton.of(btn.getTitle(), btn.getUrl(), btn.getType())
                            : CardButton.of(btn.getTitle(), btn.getUrl());
                    message.button(cardButton);
                }
            }
        } else if (StringUtils.hasText(request.getButtonTitle()) && StringUtils.hasText(request.getButtonUrl())) {
            message.button(CardButton.of(request.getButtonTitle(), request.getButtonUrl()));
        }

        if (request.getAtUserIds() != null && !request.getAtUserIds().isEmpty()) {
            message.setMention(MentionTarget.ofUserIds(request.getAtUserIds().toArray(new String[0])));
        }

        return notificationTemplate.send(request.getChannel(), message);
    }

    /**
     * 同步发送图文链接通知（跨端自适应转译为钉钉 Link、企微 News 单图文与飞书 Interactive 卡片）
     */
    public NotificationSendResult sendLink(LinkMessageRequest request) {
        LinkMessage message = LinkMessage.of(request.getTitle(), request.getText(), request.getMessageUrl(), request.getPicUrl());
        MentionTarget mentionTarget = buildMentionTarget(request.getAtAll(), request.getAtMobiles(), request.getAtUserIds());
        if (mentionTarget != null) {
            message.setMention(mentionTarget);
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
     *
     * @param request 动态模板发信请求
     * @return 发信结果
     */
    public NotificationSendResult sendTemplate(TemplateMessageRequest request) {
        return notificationTemplate.sendTemplate(request.getChannel(), request.getTemplateContent(), request.getVariables());
    }

    /**
     * 演示主备渠道顺延故障转移发信（Cascade Failover）
     *
     * @param request 故障转移演示请求
     * @return 实际执行发信的结果（包含最终成功的渠道与平台）
     */
    public NotificationSendResult sendWithFailover(FailoverDemoRequest request) {
        TextMessage message = new TextMessage(request.getContent());
        return notificationTemplate.send(request.getPrimaryChannel(), message);
    }

    /**
     * 平台原生特化 JSON 逃生舱发信
     *
     * @param request 原生报文请求
     * @return 发信结果
     */
    public NotificationSendResult sendRaw(RawMessageRequest request) {
        return notificationTemplate.send(request.getChannel(), RawMessage.of(request.getPayload()));
    }

    /**
     * 图片通知发信（支持 Base64 与本地路径零字节防卫）
     *
     * @param request 图片发信请求
     * @return 发信结果
     */
    public NotificationSendResult sendImage(ImageMessageRequest request) {
        ImageMessage message;
        if (StringUtils.hasText(request.getImagePath())) {
            message = ImageMessage.fromPath(java.nio.file.Path.of(request.getImagePath()));
        } else if (StringUtils.hasText(request.getMd5())) {
            message = ImageMessage.ofBase64(request.getBase64(), request.getMd5());
        } else {
            byte[] bytes = java.util.Base64.getDecoder().decode(request.getBase64());
            message = ImageMessage.fromBytes(bytes);
        }
        return notificationTemplate.send(request.getChannel(), message);
    }

    /**
     * 演示 NotificationContextHolder 线程级动态切源发信
     *
     * @param request 动态切源请求
     * @return 发信结果
     */
    public NotificationSendResult sendWithSwitchedChannel(ChannelSwitchRequest request) {
        TextMessage message = new TextMessage(request.getContent());
        MentionTarget mentionTarget = buildMentionTarget(request.getAtAll(), request.getAtMobiles(), request.getAtUserIds());
        if (mentionTarget != null) {
            message.setMention(mentionTarget);
        }
        try {
            return NotificationContextHolder.callWithChannel(request.getTargetChannel(), () -> notificationTemplate.send(message));
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
