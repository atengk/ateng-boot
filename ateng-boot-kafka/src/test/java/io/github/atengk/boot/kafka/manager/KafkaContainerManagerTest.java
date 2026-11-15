package io.github.atengk.boot.kafka.manager;

import io.github.atengk.boot.kafka.model.KafkaContainerStatus;
import io.github.atengk.boot.kafka.spi.KafkaRebalanceListener;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.BatchMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * KafkaContainerManager 运行期消费容器动态流控单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("KafkaContainerManager 消费容器动态流控测试")
class KafkaContainerManagerTest {

    private KafkaListenerEndpointRegistry registry;
    private KafkaContainerManager containerManager;

    @BeforeEach
    void setUp() {
        registry = mock(KafkaListenerEndpointRegistry.class);
        containerManager = new KafkaContainerManager(registry);
    }

    @Test
    @DisplayName("边界防卫：空参数校验")
    void testFailFastValidation() {
        assertThatThrownBy(() -> containerManager.pause(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idOrTopic 不能为空白");

        assertThatThrownBy(() -> containerManager.pause("   "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> containerManager.resume(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> containerManager.stop(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> containerManager.start(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> containerManager.isPaused(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> containerManager.isRunning(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("精准流控：按 Listener ID 暂停与恢复消费")
    void testPauseAndResumeById() {
        String listenerId = "orderCreateListener";
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer(listenerId)).thenReturn(container);

        when(container.isContainerPaused()).thenReturn(false, true);
        when(container.isPauseRequested()).thenReturn(false, true);

        // 1. 暂停消费
        boolean pauseResult = containerManager.pause(listenerId);
        assertThat(pauseResult).isTrue();
        verify(container, times(1)).pause();

        // 2. 状态检查
        assertThat(containerManager.isPaused(listenerId)).isTrue();

        // 3. 恢复消费
        boolean resumeResult = containerManager.resume(listenerId);
        assertThat(resumeResult).isTrue();
        verify(container, times(1)).resume();
    }

    @Test
    @DisplayName("主题订阅流控：按 Topic 名称智能解析并暂停/恢复消费")
    void testPauseAndResumeByTopic() {
        String topic = "order.payment.topic";
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        ContainerProperties properties = new ContainerProperties(topic);
        when(container.getContainerProperties()).thenReturn(properties);

        // ID 未命中，回退扫描 Topic 命中
        when(registry.getListenerContainer(topic)).thenReturn(null);
        when(registry.getListenerContainers()).thenReturn(List.of(container));

        when(container.isContainerPaused()).thenReturn(false, true);

        boolean pauseResult = containerManager.pause(topic);
        assertThat(pauseResult).isTrue();
        verify(container, times(1)).pause();

        assertThat(containerManager.isPaused(topic)).isTrue();

        boolean resumeResult = containerManager.resume(topic);
        assertThat(resumeResult).isTrue();
        verify(container, times(1)).resume();
    }

    @Test
    @DisplayName("生命周期启停：按 ID 停止与启动消费容器")
    void testStopAndStartById() {
        String listenerId = "batchLogListener";
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer(listenerId)).thenReturn(container);

        when(container.isRunning()).thenReturn(true);

        // 检查运行态
        assertThat(containerManager.isRunning(listenerId)).isTrue();

        // 停止容器
        boolean stopResult = containerManager.stop(listenerId);
        assertThat(stopResult).isTrue();
        verify(container, times(1)).stop();

        // 启动容器
        when(container.isRunning()).thenReturn(false);
        boolean startResult = containerManager.start(listenerId);
        assertThat(startResult).isTrue();
        verify(container, times(1)).start();
    }

    @Test
    @DisplayName("容错防卫：未找到目标容器时安全返回 false")
    void testTargetNotFound() {
        String unknown = "nonExistent";
        when(registry.getListenerContainer(unknown)).thenReturn(null);
        when(registry.getListenerContainers()).thenReturn(Collections.emptyList());

        assertThat(containerManager.pause(unknown)).isFalse();
        assertThat(containerManager.resume(unknown)).isFalse();
        assertThat(containerManager.stop(unknown)).isFalse();
        assertThat(containerManager.start(unknown)).isFalse();
        assertThat(containerManager.isPaused(unknown)).isFalse();
        assertThat(containerManager.isRunning(unknown)).isFalse();
    }

    @Test
    @DisplayName("批处理流控：全局暂停、恢复、启动与停止")
    void testBatchOperations() {
        MessageListenerContainer c1 = mock(MessageListenerContainer.class);
        MessageListenerContainer c2 = mock(MessageListenerContainer.class);

        when(registry.getListenerContainers()).thenReturn(List.of(c1, c2));

        when(c1.isContainerPaused()).thenReturn(false);
        when(c2.isContainerPaused()).thenReturn(false);
        when(c1.isPauseRequested()).thenReturn(false);
        when(c2.isPauseRequested()).thenReturn(false);

        // pauseAll
        int pausedCount = containerManager.pauseAll();
        assertThat(pausedCount).isEqualTo(2);
        verify(c1).pause();
        verify(c2).pause();

        // resumeAll
        when(c1.isContainerPaused()).thenReturn(true);
        when(c2.isContainerPaused()).thenReturn(true);
        int resumedCount = containerManager.resumeAll();
        assertThat(resumedCount).isEqualTo(2);
        verify(c1).resume();
        verify(c2).resume();

        // stopAll
        when(c1.isRunning()).thenReturn(true);
        when(c2.isRunning()).thenReturn(true);
        int stoppedCount = containerManager.stopAll();
        assertThat(stoppedCount).isEqualTo(2);
        verify(c1).stop();
        verify(c2).stop();

        // startAll
        when(c1.isRunning()).thenReturn(false);
        when(c2.isRunning()).thenReturn(false);
        int startedCount = containerManager.startAll();
        assertThat(startedCount).isEqualTo(2);
        verify(c1).start();
        verify(c2).start();
    }

    @Test
    @DisplayName("优雅降级：Registry 为空时所有操作保底防崩")
    void testNullRegistryGracefulDegradation() {
        KafkaContainerManager nullManager = new KafkaContainerManager(null);

        assertThat(nullManager.pauseAll()).isEqualTo(0);
        assertThat(nullManager.resumeAll()).isEqualTo(0);
        assertThat(nullManager.stopAll()).isEqualTo(0);
        assertThat(nullManager.startAll()).isEqualTo(0);
        assertThat(nullManager.getListenerContainerIds()).isEmpty();
        assertThat(nullManager.getListenerContainer("id")).isNull();
        assertThat(nullManager.findContainers("topic")).isEmpty();
        assertThat(nullManager.pause("topic")).isFalse();
        assertThat(nullManager.resume("topic")).isFalse();
        assertThat(nullManager.isPaused("topic")).isFalse();
        assertThat(nullManager.isRunning("topic")).isFalse();
    }

    @Test
    @DisplayName("SPI 扩展点：KafkaRebalanceListener 钩子调用验证")
    void testKafkaRebalanceListenerSpi() {
        AtomicBoolean assignedCalled = new AtomicBoolean(false);
        AtomicBoolean revokedCalled = new AtomicBoolean(false);
        AtomicBoolean lostCalled = new AtomicBoolean(false);

        KafkaRebalanceListener listener = new KafkaRebalanceListener() {
            @Override
            public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                assignedCalled.set(true);
            }

            @Override
            public void onPartitionsRevoked(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                revokedCalled.set(true);
            }

            @Override
            public void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                lostCalled.set(true);
            }
        };

        Consumer<?, ?> mockConsumer = mock(Consumer.class);
        List<TopicPartition> partitions = List.of(new TopicPartition("demo", 0));

        listener.onPartitionsAssigned(mockConsumer, partitions);
        assertThat(assignedCalled.get()).isTrue();

        listener.onPartitionsRevoked(mockConsumer, partitions);
        assertThat(revokedCalled.get()).isTrue();

        listener.onPartitionsLost(mockConsumer, partitions);
        assertThat(lostCalled.get()).isTrue();
    }

    @Test
    @DisplayName("状态快照：空注册表返回空不可变列表，非空集合防空且不可变")
    void testGetContainerStatusesEmptyRegistryAndImmutable() {
        KafkaContainerManager nullManager = new KafkaContainerManager(null);
        List<KafkaContainerStatus> nullStatuses = nullManager.getContainerStatuses();
        assertThat(nullStatuses).isEmpty();
        assertThatThrownBy(() -> nullStatuses.add(new KafkaContainerStatus(
                "id", "g", null, true, false, true, false, 1, 1, null, "Test"
        ))).isInstanceOf(UnsupportedOperationException.class);

        when(registry.getListenerContainerIds()).thenReturn(Collections.emptySet());
        when(registry.getListenerContainers()).thenReturn(Collections.emptyList());
        assertThat(containerManager.getContainerStatuses()).isEmpty();

        KafkaContainerStatus defensive = new KafkaContainerStatus(
                "l1", "g1", null, true, false, true, false, 2, 1, null, "ConcurrentMessageListenerContainer"
        );
        assertThat(defensive.topics()).isEmpty();
        assertThat(defensive.assignedPartitions()).isEmpty();
        assertThatThrownBy(() -> defensive.topics().add("extra"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> defensive.assignedPartitions().add("extra-0"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("状态快照：按 Listener ID 与 Topic 匹配单容器并暴露完整不可变元数据")
    @SuppressWarnings("unchecked")
    void testGetContainerStatusByIdAndTopicAndMissReturnsNull() {
        assertThatThrownBy(() -> containerManager.getContainerStatus(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idOrTopic 不能为空白");
        assertThatThrownBy(() -> containerManager.getContainerStatus("  "))
                .isInstanceOf(IllegalArgumentException.class);

        ConcurrentMessageListenerContainer<Object, Object> concurrent = mock(ConcurrentMessageListenerContainer.class);
        ContainerProperties props = new ContainerProperties("orders.topic", "orders.dlt");
        props.setMessageListener((BatchMessageListener<Object, Object>) (records) -> {});
        KafkaMessageListenerContainer<Object, Object> child1 = mock(KafkaMessageListenerContainer.class);
        KafkaMessageListenerContainer<Object, Object> child2 = mock(KafkaMessageListenerContainer.class);
        when(child1.isRunning()).thenReturn(true);
        when(child2.isRunning()).thenReturn(false);

        when(concurrent.getListenerId()).thenReturn("orderBatchListener");
        when(concurrent.getGroupId()).thenReturn("order-group");
        when(concurrent.getContainerProperties()).thenReturn(props);
        when(concurrent.isRunning()).thenReturn(true);
        when(concurrent.isContainerPaused()).thenReturn(false);
        when(concurrent.isPauseRequested()).thenReturn(true);
        when(concurrent.isAutoStartup()).thenReturn(true);
        when(concurrent.getConcurrency()).thenReturn(2);
        when(concurrent.getContainers()).thenReturn(List.of(child1, child2));
        when(concurrent.getAssignedPartitions()).thenReturn(List.of(
                new TopicPartition("orders.topic", 1),
                new TopicPartition("orders.topic", 0)
        ));

        when(registry.getListenerContainerIds()).thenReturn(Set.of("orderBatchListener"));
        when(registry.getListenerContainer("orderBatchListener")).thenReturn(concurrent);
        when(registry.getListenerContainers()).thenReturn(List.of(concurrent));

        List<KafkaContainerStatus> all = containerManager.getContainerStatuses();
        assertThat(all).hasSize(1);
        KafkaContainerStatus byId = containerManager.getContainerStatus("orderBatchListener");
        assertThat(byId).isNotNull();
        assertThat(byId.listenerId()).isEqualTo("orderBatchListener");
        assertThat(byId.groupId()).isEqualTo("order-group");
        assertThat(byId.topics()).containsExactly("orders.topic", "orders.dlt");
        assertThat(byId.running()).isTrue();
        assertThat(byId.paused()).isTrue();
        assertThat(byId.autoStartup()).isTrue();
        assertThat(byId.batchListener()).isTrue();
        assertThat(byId.concurrency()).isEqualTo(2);
        assertThat(byId.activeContainerCount()).isEqualTo(1);
        assertThat(byId.assignedPartitions()).containsExactly("orders.topic-0", "orders.topic-1");
        assertThat(byId.containerType()).isEqualTo(concurrent.getClass().getSimpleName());

        KafkaContainerStatus byTopic = containerManager.getContainerStatus("orders.topic");
        assertThat(byTopic).isEqualTo(byId);

        assertThat(containerManager.getContainerStatus("missing.topic")).isNull();
    }

    @Test
    @DisplayName("动态扩缩容：校验空白目标、非正并发数、合法扩缩、未命中返回 false 及不支持容器类型防卫")
    @SuppressWarnings("unchecked")
    void testScaleConcurrencyContractsAndGuards() {
        assertThatThrownBy(() -> containerManager.scaleConcurrency(null, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idOrTopic 不能为空白");
        assertThatThrownBy(() -> containerManager.scaleConcurrency("   ", 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> containerManager.scaleConcurrency("orders.topic", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("concurrency 必须大于 0");
        assertThatThrownBy(() -> containerManager.scaleConcurrency("orders.topic", -3))
                .isInstanceOf(IllegalArgumentException.class);

        when(registry.getListenerContainer("unknown.topic")).thenReturn(null);
        when(registry.getListenerContainers()).thenReturn(Collections.emptyList());
        assertThat(containerManager.scaleConcurrency("unknown.topic", 3)).isFalse();

        ConcurrentMessageListenerContainer<Object, Object> runningConcurrent = mock(ConcurrentMessageListenerContainer.class);
        when(runningConcurrent.getListenerId()).thenReturn("orderListener");
        when(runningConcurrent.isRunning()).thenReturn(true);
        when(runningConcurrent.isContainerPaused()).thenReturn(false);
        when(runningConcurrent.isPauseRequested()).thenReturn(false);
        when(registry.getListenerContainer("orderListener")).thenReturn(runningConcurrent);

        assertThat(containerManager.scaleConcurrency("orderListener", 4)).isTrue();
        org.mockito.InOrder order = inOrder(runningConcurrent);
        order.verify(runningConcurrent).stop();
        order.verify(runningConcurrent).setConcurrency(4);
        order.verify(runningConcurrent).start();

        MessageListenerContainer unsupported = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("legacyListener")).thenReturn(unsupported);
        assertThatThrownBy(() -> containerManager.scaleConcurrency("legacyListener", 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ConcurrentMessageListenerContainer");
        verify(unsupported, never()).stop();
    }

    @Test
    @DisplayName("动态扩缩容与快照分支：已停机仅更新并发、已暂停保持暂停、单容器及正则主题元数据映射")
    @SuppressWarnings("unchecked")
    void testScaleConcurrencyStoppedAndPausedBranchesAndSingleContainerSnapshot() {
        ConcurrentMessageListenerContainer<Object, Object> stoppedContainer = mock(ConcurrentMessageListenerContainer.class);
        when(stoppedContainer.getListenerId()).thenReturn("stoppedListener");
        when(stoppedContainer.isRunning()).thenReturn(false);
        when(registry.getListenerContainer("stoppedListener")).thenReturn(stoppedContainer);

        assertThat(containerManager.scaleConcurrency("stoppedListener", 2)).isTrue();
        verify(stoppedContainer, never()).stop();
        verify(stoppedContainer).setConcurrency(2);
        verify(stoppedContainer, never()).start();

        ConcurrentMessageListenerContainer<Object, Object> pausedRunning = mock(ConcurrentMessageListenerContainer.class);
        when(pausedRunning.getListenerId()).thenReturn("pausedListener");
        when(pausedRunning.isRunning()).thenReturn(true);
        when(pausedRunning.isContainerPaused()).thenReturn(true);
        when(registry.getListenerContainer("pausedListener")).thenReturn(pausedRunning);

        assertThat(containerManager.scaleConcurrency("pausedListener", 3)).isTrue();
        org.mockito.InOrder pausedOrder = inOrder(pausedRunning);
        pausedOrder.verify(pausedRunning).stop();
        pausedOrder.verify(pausedRunning).setConcurrency(3);
        pausedOrder.verify(pausedRunning).pause();
        pausedOrder.verify(pausedRunning).start();

        KafkaMessageListenerContainer<Object, Object> single = mock(KafkaMessageListenerContainer.class);
        ContainerProperties patternProps = new ContainerProperties(java.util.regex.Pattern.compile("audit\\..*"));
        when(single.getListenerId()).thenReturn("auditSingleListener");
        when(single.getGroupId()).thenReturn("audit-group");
        when(single.getContainerProperties()).thenReturn(patternProps);
        when(single.isRunning()).thenReturn(true);
        when(single.isAutoStartup()).thenReturn(false);
        when(registry.getListenerContainerIds()).thenReturn(Collections.emptySet());
        when(registry.getListenerContainers()).thenReturn(List.of(single));

        List<KafkaContainerStatus> statuses = containerManager.getContainerStatuses();
        assertThat(statuses).hasSize(1);
        KafkaContainerStatus singleStatus = statuses.get(0);
        assertThat(singleStatus.listenerId()).isEqualTo("auditSingleListener");
        assertThat(singleStatus.topics()).containsExactly("pattern:audit\\..*");
        assertThat(singleStatus.batchListener()).isFalse();
        assertThat(singleStatus.concurrency()).isEqualTo(1);
        assertThat(singleStatus.activeContainerCount()).isEqualTo(1);
    }
}
