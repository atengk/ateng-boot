package io.github.atengk.boot.rocketmq.listener;

import io.github.atengk.boot.rocketmq.annotation.RocketmqListener;
import io.github.atengk.boot.rocketmq.consumer.PushConsumerFactory;
import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringValueResolver;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RocketMQ 消费端监听容器与生命周期管理器单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("RocketmqConsumerContainer 监听容器单元测试")
class RocketmqConsumerContainerTest {

    @Test
    @DisplayName("扫描注册：成功扫描 @RocketmqListener，解析占位符并提取 Tag 与 SQL92 端点")
    void testScanEndpointsAndResolvePlaceholders() {
        AtengRocketmqProperties properties = new AtengRocketmqProperties();
        ObjectProvider<ClientConfiguration> clientConfigProvider = mock(ObjectProvider.class);
        PushConsumerFactory consumerFactory = mock(PushConsumerFactory.class);

        RocketmqConsumerContainer container = new RocketmqConsumerContainer(
                properties,
                clientConfigProvider,
                consumerFactory,
                null);

        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("sampleListenerBean", SampleListenerBean.class);
        context.refresh();

        container.setApplicationContext(context);
        container.setEmbeddedValueResolver(str -> {
            if ("${app.topic:order_topic}".equals(str)) {
                return "resolved_order_topic";
            }
            if ("${app.group:order_group}".equals(str)) {
                return "resolved_order_group";
            }
            return str;
        });

        container.afterSingletonsInstantiated();

        assertThat(container.getEndpoints()).hasSize(2);

        // 验证 Tag 过滤端点
        RocketmqListenerEndpoint tagEndpoint = container.getEndpoints().stream()
                .filter(e -> "resolved_order_topic".equals(e.getTopic()))
                .findFirst()
                .orElseThrow();
        assertThat(tagEndpoint.getConsumerGroup()).isEqualTo("resolved_order_group");
        assertThat(tagEndpoint.getTag()).isEqualTo("tagA || tagB");
        assertThat(tagEndpoint.getSql92()).isEmpty();
        assertThat(tagEndpoint.getConsumptionThreadCount()).isEqualTo(properties.getConsumer().getConsumptionThreadCount());

        // 验证 SQL92 过滤端点
        RocketmqListenerEndpoint sqlEndpoint = container.getEndpoints().stream()
                .filter(e -> "sql_topic".equals(e.getTopic()))
                .findFirst()
                .orElseThrow();
        assertThat(sqlEndpoint.getConsumerGroup()).isEqualTo("sql_group");
        assertThat(sqlEndpoint.getSql92()).isEqualTo("amount > 100 AND region = 'CN'");
        assertThat(sqlEndpoint.getConsumptionThreadCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("安全防卫：Topic 包含点号等非法字符时抛出 IllegalArgumentException 快速失败")
    void testTopicRegexValidationFailFast() {
        AtengRocketmqProperties properties = new AtengRocketmqProperties();
        ObjectProvider<ClientConfiguration> clientConfigProvider = mock(ObjectProvider.class);
        PushConsumerFactory consumerFactory = mock(PushConsumerFactory.class);

        RocketmqConsumerContainer container = new RocketmqConsumerContainer(
                properties,
                clientConfigProvider,
                consumerFactory,
                null);

        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("invalidTopicBean", InvalidTopicBean.class);
        context.refresh();

        container.setApplicationContext(context);

        assertThatThrownBy(container::afterSingletonsInstantiated)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Topic 名称 [invalid.order.topic] 格式非法，必须符合正则");
    }

    @Test
    @DisplayName("生命周期启动与停机排空：成功启动 PushConsumer，高 Phase 确保先停消费后关生产")
    void testSmartLifecycleStartAndStop() throws Exception {
        AtengRocketmqProperties properties = new AtengRocketmqProperties();
        ClientConfiguration clientConfiguration = mock(ClientConfiguration.class);
        ObjectProvider<ClientConfiguration> clientConfigProvider = mock(ObjectProvider.class);
        when(clientConfigProvider.getIfAvailable()).thenReturn(clientConfiguration);

        PushConsumer mockPushConsumer = mock(PushConsumer.class);
        PushConsumerFactory consumerFactory = mock(PushConsumerFactory.class);
        when(consumerFactory.createPushConsumer(
                eq(clientConfiguration),
                eq("resolved_order_group"),
                eq("resolved_order_topic"),
                any(FilterExpression.class),
                any(MessageListener.class),
                anyInt(),
                anyInt())).thenReturn(mockPushConsumer);

        RocketmqConsumerContainer container = new RocketmqConsumerContainer(
                properties,
                clientConfigProvider,
                consumerFactory,
                null);

        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("singleListenerBean", SingleListenerBean.class);
        context.refresh();

        container.setApplicationContext(context);
        container.setEmbeddedValueResolver(str -> {
            if ("${app.topic:order_topic}".equals(str)) return "resolved_order_topic";
            if ("${app.group:order_group}".equals(str)) return "resolved_order_group";
            return str;
        });

        container.afterSingletonsInstantiated();

        // 验证生命周期属性
        assertThat(container.isAutoStartup()).isTrue();
        assertThat(container.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);
        assertThat(container.isRunning()).isFalse();

        // 启动容器
        container.start();
        assertThat(container.isRunning()).isTrue();
        assertThat(container.getActiveConsumers()).contains(mockPushConsumer);

        ArgumentCaptor<FilterExpression> filterCaptor = ArgumentCaptor.forClass(FilterExpression.class);
        verify(consumerFactory).createPushConsumer(
                eq(clientConfiguration),
                eq("resolved_order_group"),
                eq("resolved_order_topic"),
                filterCaptor.capture(),
                any(MessageListener.class),
                eq(properties.getConsumer().getConsumptionThreadCount()),
                eq(properties.getConsumer().getMaxCacheMessageCount()));

        FilterExpression filter = filterCaptor.getValue();
        assertThat(filter.getFilterExpressionType()).isEqualTo(FilterExpressionType.TAG);
        assertThat(filter.getExpression()).isEqualTo("tagA || tagB");

        // 停止容器并排空在途消费
        AtomicBoolean callbackRan = new AtomicBoolean(false);
        container.stop(() -> callbackRan.set(true));

        assertThat(container.isRunning()).isFalse();
        assertThat(callbackRan.get()).isTrue();
        verify(mockPushConsumer).close();
        assertThat(container.getActiveConsumers()).isEmpty();
    }

    @Test
    @DisplayName("无连接配置时平滑降级：未配置 ClientConfiguration 时静默跳过物理 PushConsumer 创建")
    void testStartWithoutClientConfigurationGracefullySkips() {
        AtengRocketmqProperties properties = new AtengRocketmqProperties();
        ObjectProvider<ClientConfiguration> clientConfigProvider = mock(ObjectProvider.class);
        when(clientConfigProvider.getIfAvailable()).thenReturn(null);

        PushConsumerFactory consumerFactory = mock(PushConsumerFactory.class);

        RocketmqConsumerContainer container = new RocketmqConsumerContainer(
                properties,
                clientConfigProvider,
                consumerFactory,
                null);

        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("singleListenerBean", SingleListenerBean.class);
        context.refresh();

        container.setApplicationContext(context);
        container.setEmbeddedValueResolver(str -> str.replace("${app.topic:order_topic}", "order_topic").replace("${app.group:order_group}", "order_group"));
        container.afterSingletonsInstantiated();

        container.start();

        assertThat(container.isRunning()).isTrue();
        assertThat(container.getActiveConsumers()).isEmpty();
        verifyNoInteractions(consumerFactory);
    }

    // =========================================================================
    // 测试辅助 Bean
    // =========================================================================

    public static class SampleListenerBean {
        @RocketmqListener(topic = "${app.topic:order_topic}", consumerGroup = "${app.group:order_group}", tag = "tagA || tagB")
        public void onOrderMessage(String message) {}

        @RocketmqListener(topic = "sql_topic", consumerGroup = "sql_group", sql92 = "amount > 100 AND region = 'CN'", consumptionThreadCount = 10)
        public void onSqlMessage(String message) {}
    }

    public static class SingleListenerBean {
        @RocketmqListener(topic = "${app.topic:order_topic}", consumerGroup = "${app.group:order_group}", tag = "tagA || tagB")
        public void onMessage(String message) {}
    }

    public static class InvalidTopicBean {
        @RocketmqListener(topic = "invalid.order.topic", consumerGroup = "group1")
        public void onMessage(String message) {}
    }
}
