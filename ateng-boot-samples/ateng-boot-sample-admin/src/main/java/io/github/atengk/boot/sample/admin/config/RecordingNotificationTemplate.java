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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

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

    private final Deque<RecordedAlertCardVO> recordedCards = new ArrayDeque<>();
    private final ReentrantLock recordLock = new ReentrantLock();
    private final Condition cardRecordedCondition = recordLock.newCondition();
    private long totalRecordedCount = 0L;

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
        recordLock.lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(recordedCards));
        } finally {
            if (recordLock.isHeldByCurrentThread()) {
                recordLock.unlock();
            }
        }
    }

    /**
     * 获取累计录制的卡片总次数（用于无竞态并发同步等待基准）
     *
     * @return 累计录制计数
     */
    public long getTotalRecordedCount() {
        recordLock.lock();
        try {
            return totalRecordedCount;
        } finally {
            if (recordLock.isHeldByCurrentThread()) {
                recordLock.unlock();
            }
        }
    }

    /**
     * 阻塞等待直到累计录制次数超过给定基准值或超时
     *
     * @param baselineCount 基准计数
     * @param timeout       最大等待时长
     * @return true 若在超时前观测到新录制卡片
     */
    public boolean awaitRecordedCountAfter(long baselineCount, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return getTotalRecordedCount() > baselineCount;
        }
        long remainingNanos = timeout.toNanos();
        recordLock.lock();
        try {
            while (totalRecordedCount <= baselineCount && remainingNanos > 0L) {
                remainingNanos = cardRecordedCondition.awaitNanos(remainingNanos);
            }
            return totalRecordedCount > baselineCount;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return totalRecordedCount > baselineCount;
        } finally {
            if (recordLock.isHeldByCurrentThread()) {
                recordLock.unlock();
            }
        }
    }

    /**
     * 清空已录制的离线告警卡片缓存
     */
    public void clear() {
        recordLock.lock();
        try {
            recordedCards.clear();
        } finally {
            if (recordLock.isHeldByCurrentThread()) {
                recordLock.unlock();
            }
        }
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

        RecordedAlertCardVO card = RecordedAlertCardVO.builder()
                .title(title)
                .content(content)
                .recordedAt(Instant.now())
                .build();

        recordLock.lock();
        try {
            recordedCards.addFirst(card);
            while (recordedCards.size() > MAX_RECORDED_CARDS) {
                recordedCards.removeLast();
            }
            totalRecordedCount++;
            cardRecordedCondition.signalAll();
        } finally {
            if (recordLock.isHeldByCurrentThread()) {
                recordLock.unlock();
            }
        }
    }
}
