package io.github.atengk.boot.sample.admin.config;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.TransactionUtil;
import io.github.atengk.boot.notification.channel.NotificationChannelRegistry;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.MarkdownMessage;
import io.github.atengk.boot.notification.model.NotificationMessage;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.model.TextMessage;
import io.github.atengk.boot.notification.properties.NotificationProperties;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import io.github.atengk.boot.sample.admin.dto.RecordedAlertCardVO;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 离线自洽模式内存通知录制桩（仅在非 local Profile 下激活）
 * 接管出站群机器人卡片并录制于有界队列中，支持运行期 HTTP 接口回溯与自动化测试断言，消除对 @MockBean 的依赖
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Component
@Profile("!local")
public class RecordingNotificationTemplate extends NotificationTemplate {

    private static final int MAX_RECORDED_CARDS = 100;
    private static final String OFFLINE_CHANNEL = "offline-recorder";

    private final Deque<RecordedAlertCardVO> recordedCards = new ConcurrentLinkedDeque<>();

    public RecordingNotificationTemplate() {
        super(
                new NotificationProperties(),
                new NotificationChannelRegistry(OFFLINE_CHANNEL),
                Collections.emptyList(),
                null,
                RestClient.create()
        );
    }

    @Override
    public NotificationSendResult send(NotificationMessage message) {
        return send(OFFLINE_CHANNEL, message);
    }

    @Override
    public NotificationSendResult send(String channelName, NotificationMessage message) {
        BusinessAssert.notNull(message, "通知消息不能为空");
        message.validate();
        recordMessage(message);
        return NotificationSendResult.success(
                IdUtil.fastSimpleUUID(),
                channelName != null ? channelName : OFFLINE_CHANNEL,
                Platform.GENERIC,
                Duration.ZERO,
                "{\"errcode\":0,\"errmsg\":\"recorded-offline\"}"
        );
    }

    @Override
    public CompletableFuture<NotificationSendResult> sendAsync(NotificationMessage message) {
        return sendAsync(OFFLINE_CHANNEL, message);
    }

    @Override
    public CompletableFuture<NotificationSendResult> sendAsync(String channelName, NotificationMessage message) {
        return CompletableFuture.completedFuture(send(channelName, message));
    }

    @Override
    public void sendAfterCommit(NotificationMessage message) {
        sendAfterCommit(OFFLINE_CHANNEL, message);
    }

    @Override
    public void sendAfterCommit(String channelName, NotificationMessage message) {
        BusinessAssert.notNull(message, "通知消息不能为空");
        if (TransactionUtil.isSynchronizationActive()) {
            TransactionUtil.executeAfterCommit(() -> send(channelName, message));
        } else {
            send(channelName, message);
        }
    }

    @Override
    public NotificationSendResult sendTemplate(String templateContent, Map<String, Object> variables) {
        return sendTemplate(OFFLINE_CHANNEL, templateContent, variables);
    }

    @Override
    public NotificationSendResult sendTemplate(String channelName, String templateContent, Map<String, Object> variables) {
        BusinessAssert.notNull(templateContent, "模板正文不能为空");
        String rendered = getTemplateRenderer().render(templateContent, variables);
        return send(channelName, TextMessage.of(rendered));
    }

    /**
     * 获取当前已录制的告警卡片快照列表（最新记录在前）
     *
     * @return 只读卡片快照列表
     */
    public List<RecordedAlertCardVO> getRecordedCards() {
        return Collections.unmodifiableList(new ArrayList<>(recordedCards));
    }

    /**
     * 清空已录制的离线告警卡片缓存
     */
    public void clear() {
        recordedCards.clear();
    }

    private void recordMessage(NotificationMessage message) {
        String title;
        String content;
        if (message instanceof CardMessage card) {
            title = card.getTitle();
            content = card.getContent();
        } else if (message instanceof MarkdownMessage markdown) {
            title = markdown.getTitle();
            content = markdown.getContent();
        } else if (message instanceof TextMessage text) {
            title = "文本告警通知";
            content = text.getContent();
        } else {
            title = message.getClass().getSimpleName();
            content = message.toString();
        }

        recordedCards.addFirst(RecordedAlertCardVO.builder()
                .title(title)
                .content(content)
                .recordedAt(Instant.now())
                .build());

        while (recordedCards.size() > MAX_RECORDED_CARDS) {
            recordedCards.pollLast();
        }
    }
}
