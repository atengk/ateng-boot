package io.github.atengk.boot.kafka.manager;

import io.github.atengk.boot.kafka.spi.KafkaRebalanceListener;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerProperties;
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
}
