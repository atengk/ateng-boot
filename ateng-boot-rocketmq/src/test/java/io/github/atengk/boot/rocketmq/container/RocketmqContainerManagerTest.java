package io.github.atengk.boot.rocketmq.container;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.rocketmq.annotation.RocketmqListener;
import io.github.atengk.boot.rocketmq.consumer.PushConsumerFactory;
import io.github.atengk.boot.rocketmq.listener.RocketmqConsumerContainer;
import io.github.atengk.boot.rocketmq.listener.RocketmqListenerEndpoint;
import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.GenericApplicationContext;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RocketmqContainerManager 运行期消费容器动态流控与生命周期运维门面单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("RocketmqContainerManager 运行期运维与动态流控门面单元测试")
class RocketmqContainerManagerTest {

    private AtengRocketmqProperties properties;
    private ClientConfiguration clientConfiguration;
    private ObjectProvider<ClientConfiguration> clientConfigProvider;
    private PushConsumerFactory consumerFactory;
    private PushConsumer mockConsumer1;
    private PushConsumer mockConsumer2;
    private RocketmqConsumerContainer container;
    private RocketmqContainerManager manager;
    private TestListenerBean listenerBean;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        properties = new AtengRocketmqProperties();
        clientConfiguration = mock(ClientConfiguration.class);
        clientConfigProvider = mock(ObjectProvider.class);
        when(clientConfigProvider.getIfAvailable()).thenReturn(clientConfiguration);

        consumerFactory = mock(PushConsumerFactory.class);
        mockConsumer1 = mock(PushConsumer.class);
        mockConsumer2 = mock(PushConsumer.class);

        when(consumerFactory.createPushConsumer(
                eq(clientConfiguration),
                eq("order_group"),
                eq("order_topic"),
                any(FilterExpression.class),
                any(MessageListener.class),
                anyInt(),
                anyInt())).thenReturn(mockConsumer1);

        when(consumerFactory.createPushConsumer(
                eq(clientConfiguration),
                eq("pay_group"),
                eq("pay_topic"),
                any(FilterExpression.class),
                any(MessageListener.class),
                anyInt(),
                anyInt())).thenReturn(mockConsumer2);

        container = new RocketmqConsumerContainer(
                properties,
                clientConfigProvider,
                consumerFactory,
                null);

        GenericApplicationContext context = new GenericApplicationContext();
        listenerBean = new TestListenerBean();
        context.registerBean("testListenerBean", TestListenerBean.class, () -> listenerBean);
        context.refresh();

        container.setApplicationContext(context);
        container.afterSingletonsInstantiated();

        manager = new RocketmqContainerManager(container);
    }

    @Test
    @DisplayName("端点检索与审计：成功查询 endpointId、端点列表、根据 ID/Topic 检索")
    void testEndpointLookupAndVisibility() {
        Set<String> ids = manager.getEndpointIds();
        assertThat(ids).containsExactlyInAnyOrder("custom-order-endpoint", "pay_group#pay_topic");

        List<RocketmqListenerEndpoint> endpoints = manager.getEndpoints();
        assertThat(endpoints).hasSize(2);

        RocketmqListenerEndpoint orderEp = manager.getEndpoint("custom-order-endpoint");
        assertThat(orderEp).isNotNull();
        assertThat(orderEp.getTopic()).isEqualTo("order_topic");
        assertThat(orderEp.getConsumerGroup()).isEqualTo("order_group");

        RocketmqListenerEndpoint payEp = manager.getEndpoint("pay_group#pay_topic");
        assertThat(payEp).isNotNull();
        assertThat(payEp.getTopic()).isEqualTo("pay_topic");

        // 智能匹配: 按 ID
        List<RocketmqListenerEndpoint> matchedById = manager.findEndpoints("custom-order-endpoint");
        assertThat(matchedById).hasSize(1).contains(orderEp);

        // 智能匹配: 按 Topic
        List<RocketmqListenerEndpoint> matchedByTopic = manager.findEndpoints("pay_topic");
        assertThat(matchedByTopic).hasSize(1).contains(payEp);

        // 未匹配
        assertThat(manager.findEndpoints("non_existent")).isEmpty();
        assertThat(manager.getEndpoint("non_existent")).isNull();
    }

    @Test
    @DisplayName("动态暂停与恢复：按 endpointId 精确管控")
    void testPauseAndResumeByEndpointId() {
        assertThat(manager.isPaused("custom-order-endpoint")).isFalse();

        // 暂停
        boolean pauseResult = manager.pause("custom-order-endpoint");
        assertThat(pauseResult).isTrue();
        assertThat(manager.isPaused("custom-order-endpoint")).isTrue();

        // 恢复
        boolean resumeResult = manager.resume("custom-order-endpoint");
        assertThat(resumeResult).isTrue();
        assertThat(manager.isPaused("custom-order-endpoint")).isFalse();
    }

    @Test
    @DisplayName("动态暂停与恢复：按 Topic 匹配管控")
    void testPauseAndResumeByTopic() {
        assertThat(manager.isPaused("order_topic")).isFalse();

        boolean pauseResult = manager.pause("order_topic");
        assertThat(pauseResult).isTrue();
        assertThat(manager.isPaused("order_topic")).isTrue();

        boolean resumeResult = manager.resume("order_topic");
        assertThat(resumeResult).isTrue();
        assertThat(manager.isPaused("order_topic")).isFalse();
    }

    @Test
    @DisplayName("全局暂停与恢复：pauseAll 与 resumeAll 批量流控")
    void testPauseAllAndResumeAll() {
        assertThat(manager.isPaused("custom-order-endpoint")).isFalse();
        assertThat(manager.isPaused("pay_group#pay_topic")).isFalse();

        int pausedCount = manager.pauseAll();
        assertThat(pausedCount).isEqualTo(2);
        assertThat(manager.isPaused("custom-order-endpoint")).isTrue();
        assertThat(manager.isPaused("pay_group#pay_topic")).isTrue();

        int resumedCount = manager.resumeAll();
        assertThat(resumedCount).isEqualTo(2);
        assertThat(manager.isPaused("custom-order-endpoint")).isFalse();
        assertThat(manager.isPaused("pay_group#pay_topic")).isFalse();
    }

    @Test
    @DisplayName("暂停流控防卫实测：暂停期间 consume 立即返回 FAILURE 触发 Broker 退避，恢复后正常消费")
    void testPausedEndpointConsumeBehavior() {
        RocketmqListenerEndpoint endpoint = manager.getEndpoint("custom-order-endpoint");
        assertThat(endpoint).isNotNull();

        MessageView mockMsg = createMockMessageView("order_topic", "MSG-ORDER-1", "order-data".getBytes(StandardCharsets.UTF_8));

        // 1. 正常状态消费成功
        ConsumeResult result1 = endpoint.getInvoker().consume(mockMsg);
        assertThat(result1).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(listenerBean.getOrderConsumeCount()).isEqualTo(1);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();

        // 2. 暂停状态直接返回 FAILURE，不执行业务反射，且 MDC 上下文严格清理
        manager.pause("custom-order-endpoint");
        ConsumeResult result2 = endpoint.getInvoker().consume(mockMsg);
        assertThat(result2).isEqualTo(ConsumeResult.FAILURE);
        assertThat(listenerBean.getOrderConsumeCount()).isEqualTo(1); // 计数未增加
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();

        // 3. 恢复消费状态
        manager.resume("custom-order-endpoint");
        ConsumeResult result3 = endpoint.getInvoker().consume(mockMsg);
        assertThat(result3).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(listenerBean.getOrderConsumeCount()).isEqualTo(2); // 恢复增加
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    @DisplayName("物理生命周期启停：stop 关闭底层 PushConsumer，start 重新拉起激活")
    void testStopAndStartLifecycle() throws Exception {
        // 先启动容器
        container.start();
        assertThat(manager.isRunning("custom-order-endpoint")).isTrue();
        assertThat(manager.isRunning("pay_group#pay_topic")).isTrue();

        // 停止指定端点
        boolean stopResult = manager.stop("custom-order-endpoint");
        assertThat(stopResult).isTrue();
        assertThat(manager.isRunning("custom-order-endpoint")).isFalse();
        assertThat(manager.isRunning("pay_group#pay_topic")).isTrue();
        verify(mockConsumer1).close();

        // 重新启动指定端点
        boolean startResult = manager.start("custom-order-endpoint");
        assertThat(startResult).isTrue();
        assertThat(manager.isRunning("custom-order-endpoint")).isTrue();
        verify(consumerFactory, times(2)).createPushConsumer(
                eq(clientConfiguration),
                eq("order_group"),
                eq("order_topic"),
                any(),
                any(),
                anyInt(),
                anyInt());
    }

    @Test
    @DisplayName("批量生命周期启停：stopAll 与 startAll")
    void testStopAllAndStartAll() throws Exception {
        container.start();
        assertThat(manager.isRunning("custom-order-endpoint")).isTrue();
        assertThat(manager.isRunning("pay_group#pay_topic")).isTrue();

        int stoppedCount = manager.stopAll();
        assertThat(stoppedCount).isEqualTo(2);
        assertThat(manager.isRunning("custom-order-endpoint")).isFalse();
        assertThat(manager.isRunning("pay_group#pay_topic")).isFalse();
        verify(mockConsumer1).close();
        verify(mockConsumer2).close();

        int startedCount = manager.startAll();
        assertThat(startedCount).isEqualTo(2);
        assertThat(manager.isRunning("custom-order-endpoint")).isTrue();
        assertThat(manager.isRunning("pay_group#pay_topic")).isTrue();
    }

    @Test
    @DisplayName("边界与降级防卫：未知端点、空参数与 null 容器平滑防御")
    void testEdgeCasesAndDefensiveBehavior() {
        // 未知端点操作返回 false
        assertThat(manager.pause("unknown-endpoint")).isFalse();
        assertThat(manager.resume("unknown-endpoint")).isFalse();
        assertThat(manager.start("unknown-endpoint")).isFalse();
        assertThat(manager.stop("unknown-endpoint")).isFalse();
        assertThat(manager.isPaused("unknown-endpoint")).isFalse();
        assertThat(manager.isRunning("unknown-endpoint")).isFalse();

        // 空入参快速失败
        assertThatThrownBy(() -> manager.pause(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.resume("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.start(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.stop(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.isPaused(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.isRunning(""))
                .isInstanceOf(IllegalArgumentException.class);

        // null container 实例安全防御
        RocketmqContainerManager nullManager = new RocketmqContainerManager(null);
        assertThat(nullManager.getEndpointIds()).isEmpty();
        assertThat(nullManager.getEndpoints()).isEmpty();
        assertThat(nullManager.getEndpoint("custom-order-endpoint")).isNull();
        assertThat(nullManager.findEndpoints("order_topic")).isEmpty();
        assertThat(nullManager.pauseAll()).isEqualTo(0);
        assertThat(nullManager.resumeAll()).isEqualTo(0);
        assertThat(nullManager.stopAll()).isEqualTo(0);
        assertThat(nullManager.startAll()).isEqualTo(0);
        assertThat(nullManager.pause("test")).isFalse();
        assertThat(nullManager.resume("test")).isFalse();
        assertThat(nullManager.start("test")).isFalse();
        assertThat(nullManager.stop("test")).isFalse();
        assertThat(nullManager.isPaused("test")).isFalse();
        assertThat(nullManager.isRunning("test")).isFalse();
    }

    private MessageView createMockMessageView(String topic, String messageIdStr, byte[] bodyBytes) {
        MessageView msg = mock(MessageView.class);
        when(msg.getTopic()).thenReturn(topic);
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn(messageIdStr);
        when(msg.getMessageId()).thenReturn(messageId);
        when(msg.getBody()).thenAnswer(inv -> ByteBuffer.wrap(bodyBytes));
        when(msg.getTag()).thenReturn(Optional.of("testTag"));
        Map<String, String> props = new HashMap<>();
        props.put(MessageHeaderConstants.TRACE_ID, "trace-container-mgr-123");
        when(msg.getProperties()).thenReturn(props);
        return msg;
    }

    public static class TestListenerBean {
        private final AtomicInteger orderConsumeCount = new AtomicInteger(0);
        private final AtomicInteger payConsumeCount = new AtomicInteger(0);

        @RocketmqListener(id = "custom-order-endpoint", topic = "order_topic", consumerGroup = "order_group")
        public void onOrderMessage(String message) {
            orderConsumeCount.incrementAndGet();
        }

        @RocketmqListener(topic = "pay_topic", consumerGroup = "pay_group")
        public void onPayMessage(String message) {
            payConsumeCount.incrementAndGet();
        }

        public int getOrderConsumeCount() {
            return orderConsumeCount.get();
        }

        public int getPayConsumeCount() {
            return payConsumeCount.get();
        }
    }
}
