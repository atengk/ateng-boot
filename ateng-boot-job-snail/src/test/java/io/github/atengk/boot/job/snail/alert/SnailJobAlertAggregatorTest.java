package io.github.atengk.boot.job.snail.alert;

import io.github.atengk.boot.job.snail.event.SnailJobAlertEvent;
import io.github.atengk.boot.job.snail.event.SnailRetryDeadLetterEvent;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import io.github.atengk.boot.job.snail.retry.callback.SnailRetryDeadLetterCallback;
import io.github.atengk.boot.notification.model.CardColor;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SnailJobAlertAggregator 三阶告警防风暴聚合与自愈引擎单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailJobAlertAggregatorTest {

    private SnailJobProperties properties;
    private ApplicationEventPublisher eventPublisher;
    private NotificationTemplate notificationTemplate;
    private SnailJobAlertAggregator aggregator;
    private List<Object> publishedEvents;

    @BeforeEach
    public void setUp() {
        properties = new SnailJobProperties();
        properties.getAlert().setEnabled(true);
        properties.getAlert().setSuppressThreshold(3);
        properties.getAlert().setSuppressDuration(Duration.ofMinutes(5));

        publishedEvents = new ArrayList<>();
        eventPublisher = event -> publishedEvents.add(event);

        notificationTemplate = Mockito.mock(NotificationTemplate.class);
        aggregator = new SnailJobAlertAggregator(properties, eventPublisher, notificationTemplate);
    }

    @Test
    public void testThreeStageFailureAndRecoveryForJob() {
        // 阶段一：首发失败立即推送
        aggregator.onJobFailure("jobA", new RuntimeException("DB Connection Refused"));

        assertEquals(1, publishedEvents.size());
        assertTrue(publishedEvents.get(0) instanceof SnailJobAlertEvent);
        SnailJobAlertEvent firstEvent = (SnailJobAlertEvent) publishedEvents.get(0);
        assertEquals(SnailJobAlertEvent.AlertType.INITIAL_FAILURE, firstEvent.getAlertType());
        assertEquals("jobA", firstEvent.getJobName());

        // 阶段二：持续失败被平滑抑制 (第2次失败，未达 threshold 3)
        aggregator.onJobFailure("jobA", new RuntimeException("DB Connection Refused"));
        assertEquals(1, publishedEvents.size(), "第2次失败应当被抑制");

        // 达到连续失败阈值 (第3次失败，diff=3 >= threshold(3) -> 触发摘要)
        aggregator.onJobFailure("jobA", new RuntimeException("DB Connection Refused"));
        assertEquals(2, publishedEvents.size(), "第3次失败达到阈值应当触发摘要");

        SnailJobAlertEvent summaryEvent = (SnailJobAlertEvent) publishedEvents.get(1);
        assertEquals(SnailJobAlertEvent.AlertType.FAILURE_SUMMARY, summaryEvent.getAlertType());
        assertEquals("jobA", summaryEvent.getJobName());
        assertEquals(3, summaryEvent.getFailureCount());

        // 阶段三：故障自愈恢复通知
        aggregator.onJobSuccess("jobA");
        assertEquals(3, publishedEvents.size(), "自愈恢复应当触发通知");

        SnailJobAlertEvent recoveryEvent = (SnailJobAlertEvent) publishedEvents.get(2);
        assertEquals(SnailJobAlertEvent.AlertType.RECOVERY, recoveryEvent.getAlertType());
        assertEquals("jobA", recoveryEvent.getJobName());
        assertEquals(3, recoveryEvent.getFailureCount());

        // 正常状态下的成功执行不触发任何事件
        aggregator.onJobSuccess("jobA");
        assertEquals(3, publishedEvents.size(), "未处于故障状态的成功执行不发事件");

        // 验证三阶段卡片推送颜色依序为 RED -> ORANGE -> GREEN
        ArgumentCaptor<CardMessage> cardCaptor = ArgumentCaptor.forClass(CardMessage.class);
        verify(notificationTemplate, times(3)).sendAsync(cardCaptor.capture());
        List<CardMessage> cards = cardCaptor.getAllValues();
        assertEquals(3, cards.size());
        assertEquals(CardColor.RED, cards.get(0).getHeaderColor());
        assertEquals(CardColor.ORANGE, cards.get(1).getHeaderColor());
        assertEquals(CardColor.GREEN, cards.get(2).getHeaderColor());
    }

    @Test
    public void testThreeStageFailureAndRecoveryForRetry() {
        // 重试场景失败
        aggregator.onRetryFailure("orderRetryScene", new IllegalStateException("Timeout"));
        assertEquals(1, publishedEvents.size());
        SnailJobAlertEvent event = (SnailJobAlertEvent) publishedEvents.get(0);
        assertEquals(SnailJobAlertEvent.AlertType.INITIAL_FAILURE, event.getAlertType());
        assertEquals("orderRetryScene", event.getJobName());

        // 重试场景自愈
        aggregator.onRetrySuccess("orderRetryScene");
        assertEquals(2, publishedEvents.size());
        SnailJobAlertEvent recoveryEvent = (SnailJobAlertEvent) publishedEvents.get(1);
        assertEquals(SnailJobAlertEvent.AlertType.RECOVERY, recoveryEvent.getAlertType());
    }

    @Test
    public void testDeadLetterIncident() {
        aggregator.onRetryDeadLetter(
                "paymentScene",
                "idempotent-999",
                "BIZ-1001",
                "com.example.PaymentService",
                "{\"amount\":100}",
                5,
                new RuntimeException("Exceeded maximum retries"),
                "trace-test-123"
        );

        assertEquals(1, publishedEvents.size());
        assertTrue(publishedEvents.get(0) instanceof SnailRetryDeadLetterEvent);
        SnailRetryDeadLetterEvent deadLetterEvent = (SnailRetryDeadLetterEvent) publishedEvents.get(0);
        assertEquals("paymentScene", deadLetterEvent.getScene());
        assertEquals("idempotent-999", deadLetterEvent.getIdempotentId());
        assertEquals("BIZ-1001", deadLetterEvent.getBizNo());
        assertEquals(5, deadLetterEvent.getRetryCount());
        assertEquals("trace-test-123", deadLetterEvent.getTraceId());

        ArgumentCaptor<CardMessage> cardCaptor = ArgumentCaptor.forClass(CardMessage.class);
        verify(notificationTemplate, times(1)).sendAsync(cardCaptor.capture());
        assertEquals(CardColor.RED, cardCaptor.getValue().getHeaderColor());
        assertTrue(cardCaptor.getValue().getTitle().contains("死信"));
    }

    @Test
    public void testRetryDeadLetterCallback() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("alertAggregator", aggregator);

        SnailRetryDeadLetterCallback callback = new SnailRetryDeadLetterCallback(beanFactory.getBeanProvider(SnailJobAlertAggregator.class));

        callback.doMaxRetryCallback("userSyncScene", "UserService", new Object[]{"userId=123"});
        assertEquals(1, publishedEvents.size());
        assertTrue(publishedEvents.get(0) instanceof SnailRetryDeadLetterEvent);
        SnailRetryDeadLetterEvent event = (SnailRetryDeadLetterEvent) publishedEvents.get(0);
        assertEquals("userSyncScene", event.getScene());
        assertEquals("UserService", event.getExecutorClassName());

        callback.doSuccessCallback("userSyncScene", "UserService", new Object[]{"userId=123"});
        // Since it wasn't in failure state for aggregator's onRetrySuccess, no event
        assertEquals(1, publishedEvents.size());
    }

    @Test
    public void testAlertDisabledDoesNothing() {
        properties.getAlert().setEnabled(false);
        aggregator.onJobFailure("jobB", new RuntimeException("Error"));
        aggregator.onJobSuccess("jobB");
        aggregator.onRetryFailure("sceneB", new RuntimeException("Error"));
        aggregator.onRetrySuccess("sceneB");

        assertTrue(publishedEvents.isEmpty());
        verify(notificationTemplate, never()).sendAsync(any());
    }
}
