package io.github.atengk.boot.rabbitmq.manager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RabbitContainerManager 单元测试
 *
 * @author Ateng
 * @since 2026-11-15
 */
@DisplayName("RabbitContainerManager 单元测试")
class RabbitContainerManagerTest {

    @Test
    @DisplayName("支持按容器 ID、单队列及同队列多容器启停与状态查询")
    void shouldStartAndStopByListenerIdOrQueueAndMultiContainer() {
        RabbitListenerEndpointRegistry registry = new RabbitListenerEndpointRegistry();
        InMemorySimpleContainer orderContainer = registerSimpleContainer(registry, "listener-order", true, "q.order");
        InMemorySimpleContainer logContainerA = registerSimpleContainer(registry, "listener-log-a", true, "q.log");
        InMemorySimpleContainer logContainerB = registerSimpleContainer(registry, "listener-log-b", true, "q.log");

        RabbitContainerManager manager = new RabbitContainerManager(registry);

        assertThat(manager.getListenerContainerIds())
                .containsExactlyInAnyOrder("listener-order", "listener-log-a", "listener-log-b");
        assertThat(manager.findContainers("q.log")).hasSize(2);
        assertThat(manager.isRunning("listener-order")).isTrue();

        manager.stop("listener-order");
        assertThat(orderContainer.isRunning()).isFalse();
        assertThat(manager.isRunning("listener-order")).isFalse();

        manager.start("listener-order");
        assertThat(orderContainer.isRunning()).isTrue();

        manager.stop("q.log");
        assertThat(logContainerA.isRunning()).isFalse();
        assertThat(logContainerB.isRunning()).isFalse();
        assertThat(manager.isRunning("q.log")).isFalse();

        manager.start("q.log");
        assertThat(logContainerA.isRunning()).isTrue();
        assertThat(logContainerB.isRunning()).isTrue();
        assertThat(manager.isRunning("q.log")).isTrue();
    }

    @Test
    @DisplayName("支持 startAll 与 stopAll 全量容器启停控制")
    void shouldStartAllAndStopAllContainers() {
        RabbitListenerEndpointRegistry registry = new RabbitListenerEndpointRegistry();
        InMemorySimpleContainer c1 = registerSimpleContainer(registry, "c1", true, "q1");
        InMemorySimpleContainer c2 = registerSimpleContainer(registry, "c2", false, "q2");

        RabbitContainerManager manager = new RabbitContainerManager(registry);

        manager.stopAll();
        assertThat(c1.isRunning()).isFalse();
        assertThat(c2.isRunning()).isFalse();

        manager.startAll();
        assertThat(c1.isRunning()).isTrue();
        assertThat(c2.isRunning()).isTrue();
    }

    @Test
    @DisplayName("支持 scaleConcurrency 与 updatePrefetchCount 运行期扩缩容并刷新快照")
    void shouldScaleConcurrencyAndUpdatePrefetchCount() {
        RabbitListenerEndpointRegistry registry = new RabbitListenerEndpointRegistry();
        InMemorySimpleContainer container = registerSimpleContainer(registry, "listener-scale", true, "q.scale");
        container.setSimulatedActiveConsumers(2);

        RabbitContainerManager manager = new RabbitContainerManager(registry);

        manager.scaleConcurrency("q.scale", 3, 6);
        assertThat(readField(container, "concurrentConsumers")).isEqualTo(3);
        assertThat(readField(container, "maxConcurrentConsumers")).isEqualTo(6);

        manager.scaleConcurrency("listener-scale", 1, 2);
        assertThat(readField(container, "concurrentConsumers")).isEqualTo(1);
        assertThat(readField(container, "maxConcurrentConsumers")).isEqualTo(2);

        manager.updatePrefetchCount("listener-scale", 48);
        RabbitContainerStatus status = manager.getContainerStatus("listener-scale");
        assertThat(status.listenerId()).isEqualTo("listener-scale");
        assertThat(status.queueNames()).containsExactly("q.scale");
        assertThat(status.running()).isTrue();
        assertThat(status.autoStartup()).isTrue();
        assertThat(status.activeConsumerCount()).isEqualTo(2);
        assertThat(status.prefetchCount()).isEqualTo(48);
        assertThat(status.containerType()).isEqualTo(InMemorySimpleContainer.class.getSimpleName());

        List<RabbitContainerStatus> allStatuses = manager.getContainerStatuses();
        assertThat(allStatuses).hasSize(1);
        assertThatThrownBy(() -> status.queueNames().add("illegal"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("空入参、未命中容器、非法并发范围或非法预取数触发 Fail-Fast 异常")
    void shouldFailFastOnInvalidInputsOrMissingTargets() {
        RabbitListenerEndpointRegistry registry = new RabbitListenerEndpointRegistry();
        registerSimpleContainer(registry, "listener-1", true, "q.1");

        MessageListenerContainer nonSimple = mock(MessageListenerContainer.class);
        when(nonSimple.isRunning()).thenReturn(true);
        SimpleRabbitListenerEndpoint customEndpoint = new SimpleRabbitListenerEndpoint();
        customEndpoint.setId("custom-non-simple");
        customEndpoint.setQueueNames("q.custom");
        RabbitListenerContainerFactory<?> customFactory = ep -> nonSimple;
        registry.registerListenerContainer(customEndpoint, customFactory, true);

        RabbitContainerManager manager = new RabbitContainerManager(registry);

        assertThatThrownBy(() -> manager.stop(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.start(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.stop("not-exist-target"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.start("not-exist-target"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.getContainerStatus("not-exist-target"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> manager.scaleConcurrency("listener-1", 0, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.scaleConcurrency("listener-1", 4, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.scaleConcurrency("not-exist-target", 1, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.scaleConcurrency("custom-non-simple", 1, 2))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> manager.updatePrefetchCount("listener-1", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.updatePrefetchCount("listener-1", 65536))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.updatePrefetchCount("not-exist-target", 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.updatePrefetchCount("custom-non-simple", 10))
                .isInstanceOf(IllegalStateException.class);

        RabbitContainerManager nullRegistryManager = new RabbitContainerManager((RabbitListenerEndpointRegistry) null);
        assertThat(nullRegistryManager.getListenerContainer("listener-1")).isNull();
        assertThat(nullRegistryManager.getListenerContainerIds()).isEmpty();
        assertThat(nullRegistryManager.findContainers("listener-1")).isEmpty();
        assertThat(nullRegistryManager.getContainerStatuses()).isEmpty();
        nullRegistryManager.startAll();
        nullRegistryManager.stopAll();
    }

    @Test
    @DisplayName("getQueueStatus 正常查询及 RabbitAdmin 缺失/队列不存在/异常时零值安全降级")
    void shouldQueryQueueStatusAndDegradeSafelyWhenAdminMissingOrQueueAbsent() {
        RabbitListenerEndpointRegistry registry = new RabbitListenerEndpointRegistry();

        RabbitContainerManager noAdminManager = new RabbitContainerManager(registry, null);
        RabbitQueueStatus missingAdminStatus = noAdminManager.getQueueStatus("q.order");
        assertThat(missingAdminStatus).isEqualTo(new RabbitQueueStatus("q.order", 0L, 0, false));

        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        when(rabbitAdmin.getQueueInfo("q.order")).thenReturn(new QueueInformation("q.order", 15, 3));
        when(rabbitAdmin.getQueueInfo("q.absent")).thenReturn(null);
        when(rabbitAdmin.getQueueInfo("q.error")).thenThrow(new AmqpIOException(new IOException("broker down")));

        RabbitContainerManager manager = new RabbitContainerManager(registry, rabbitAdmin);

        RabbitQueueStatus existing = manager.getQueueStatus("q.order");
        assertThat(existing).isEqualTo(new RabbitQueueStatus("q.order", 15L, 3, true));

        RabbitQueueStatus absent = manager.getQueueStatus("q.absent");
        assertThat(absent).isEqualTo(new RabbitQueueStatus("q.absent", 0L, 0, false));

        RabbitQueueStatus errored = manager.getQueueStatus("q.error");
        assertThat(errored).isEqualTo(new RabbitQueueStatus("q.error", 0L, 0, false));

        assertThatThrownBy(() -> manager.getQueueStatus(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private InMemorySimpleContainer registerSimpleContainer(
            RabbitListenerEndpointRegistry registry,
            String listenerId,
            boolean initiallyRunning,
            String... queues) {
        InMemorySimpleContainer container = new InMemorySimpleContainer(initiallyRunning);
        container.setListenerId(listenerId);
        container.setQueueNames(queues);
        container.setAutoStartup(true);

        SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setId(listenerId);
        endpoint.setQueueNames(queues);
        RabbitListenerContainerFactory<?> factory = ep -> container;
        registry.registerListenerContainer(endpoint, factory, false);
        return container;
    }

    private Object readField(Object target, String fieldName) {
        return new DirectFieldAccessor(target).getPropertyValue(fieldName);
    }

    static class InMemorySimpleContainer extends SimpleMessageListenerContainer {
        private int simulatedActiveConsumers = 1;

        InMemorySimpleContainer(boolean initiallyRunning) {
            super(mock(ConnectionFactory.class));
            ReflectionTestUtils.setField(this, "running", initiallyRunning);
            ReflectionTestUtils.setField(this, "initialized", true);
        }

        void setSimulatedActiveConsumers(int count) {
            this.simulatedActiveConsumers = count;
        }

        @Override
        public void afterPropertiesSet() {
            // 内存测试替身跳过真实 AMQP 通道初始化
        }

        @Override
        public void start() {
            ReflectionTestUtils.setField(this, "running", true);
        }

        @Override
        public void stop() {
            ReflectionTestUtils.setField(this, "running", false);
        }

        @Override
        public int getActiveConsumerCount() {
            return isRunning() ? simulatedActiveConsumers : 0;
        }
    }
}
