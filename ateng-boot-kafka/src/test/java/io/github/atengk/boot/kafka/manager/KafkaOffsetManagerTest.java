package io.github.atengk.boot.kafka.manager;

import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.kafka.model.ConsumerGroupLag;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * KafkaOffsetManager 位移治理与实时 Lag 积压探测单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("KafkaOffsetManager 位移治理与积压探测测试")
class KafkaOffsetManagerTest {

    private AdminClient adminClient;
    private KafkaOffsetManager offsetManager;

    @BeforeEach
    void setUp() {
        adminClient = mock(AdminClient.class);
        offsetManager = new KafkaOffsetManager(() -> adminClient, Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("边界防卫：空参数 Fail-Fast 校验")
    void testFailFastValidation() {
        assertThatThrownBy(() -> offsetManager.getConsumerGroupOffsets(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupId 不能为空白");

        assertThatThrownBy(() -> offsetManager.getConsumerGroupOffsets("   "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> offsetManager.getConsumerGroupOffsets("grp", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic 不能为空白");

        assertThatThrownBy(() -> offsetManager.resetOffsetsToEarliest(null, "topic"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> offsetManager.resetOffsetsToLatest("grp", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> offsetManager.resetOffsetsToTimestamp("grp", "topic", (Instant) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamp 不能为空");

        assertThatThrownBy(() -> offsetManager.resetOffsetsToTimestamp("grp", "topic", -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestampMillis 必须大于等于 0");

        assertThatThrownBy(() -> offsetManager.getConsumerGroupLag(null, "topic"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> offsetManager.getConsumerGroupLag("grp", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("位移查询：全量消费组位移查询并保证物理连接闭环释放")
    void testGetConsumerGroupOffsetsAll() {
        String groupId = "order-group";
        TopicPartition tp0 = new TopicPartition("order.topic", 0);
        TopicPartition tp1 = new TopicPartition("order.topic", 1);
        TopicPartition userTp = new TopicPartition("user.topic", 0);

        Map<TopicPartition, OffsetAndMetadata> mockOffsets = Map.of(
                tp0, new OffsetAndMetadata(100L),
                tp1, new OffsetAndMetadata(200L),
                userTp, new OffsetAndMetadata(50L)
        );

        ListConsumerGroupOffsetsResult result = mock(ListConsumerGroupOffsetsResult.class);
        when(result.partitionsToOffsetAndMetadata()).thenReturn(KafkaFuture.completedFuture(mockOffsets));
        when(adminClient.listConsumerGroupOffsets(groupId)).thenReturn(result);

        Map<TopicPartition, OffsetAndMetadata> offsets = offsetManager.getConsumerGroupOffsets(groupId);

        assertThat(offsets).hasSize(3);
        assertThat(offsets.get(tp0).offset()).isEqualTo(100L);
        assertThat(offsets.get(tp1).offset()).isEqualTo(200L);
        assertThat(offsets.get(userTp).offset()).isEqualTo(50L);

        // 验证 try-with-resources 确定性物理关闭
        verify(adminClient).close();
    }

    @Test
    @DisplayName("位移查询：按主题过滤当前消费组位移")
    void testGetConsumerGroupOffsetsForTopic() {
        String groupId = "order-group";
        String topic = "order.topic";
        TopicPartition tp0 = new TopicPartition("order.topic", 0);
        TopicPartition userTp = new TopicPartition("user.topic", 0);

        Map<TopicPartition, OffsetAndMetadata> mockOffsets = Map.of(
                tp0, new OffsetAndMetadata(100L),
                userTp, new OffsetAndMetadata(50L)
        );

        ListConsumerGroupOffsetsResult result = mock(ListConsumerGroupOffsetsResult.class);
        when(result.partitionsToOffsetAndMetadata()).thenReturn(KafkaFuture.completedFuture(mockOffsets));
        when(adminClient.listConsumerGroupOffsets(groupId)).thenReturn(result);

        Map<TopicPartition, OffsetAndMetadata> offsets = offsetManager.getConsumerGroupOffsets(groupId, topic);

        assertThat(offsets).hasSize(1);
        assertThat(offsets.get(tp0).offset()).isEqualTo(100L);
        assertThat(offsets.containsKey(userTp)).isFalse();

        verify(adminClient).close();
    }

    @Test
    @DisplayName("位移重置：重置至最早有效位移 Earliest")
    @SuppressWarnings("unchecked")
    void testResetOffsetsToEarliest() {
        String groupId = "order-group";
        String topic = "order.topic";
        mockTopicPartitions(topic, 2);

        TopicPartition tp0 = new TopicPartition(topic, 0);
        TopicPartition tp1 = new TopicPartition(topic, 1);

        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> listOffsetsMap = Map.of(
                tp0, new ListOffsetsResult.ListOffsetsResultInfo(0L, 1000L, Optional.empty()),
                tp1, new ListOffsetsResult.ListOffsetsResultInfo(10L, 1000L, Optional.empty())
        );

        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        when(listOffsetsResult.all()).thenReturn(KafkaFuture.completedFuture(listOffsetsMap));
        when(adminClient.listOffsets(anyMap())).thenReturn(listOffsetsResult);

        AlterConsumerGroupOffsetsResult alterResult = mock(AlterConsumerGroupOffsetsResult.class);
        when(alterResult.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(adminClient.alterConsumerGroupOffsets(eq(groupId), anyMap())).thenReturn(alterResult);

        Map<TopicPartition, OffsetAndMetadata> result = offsetManager.resetOffsetsToEarliest(groupId, topic);

        assertThat(result).hasSize(2);
        assertThat(result.get(tp0).offset()).isEqualTo(0L);
        assertThat(result.get(tp1).offset()).isEqualTo(10L);

        ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> captor = ArgumentCaptor.forClass(Map.class);
        verify(adminClient).alterConsumerGroupOffsets(eq(groupId), captor.capture());
        assertThat(captor.getValue().get(tp0).offset()).isEqualTo(0L);
        assertThat(captor.getValue().get(tp1).offset()).isEqualTo(10L);

        verify(adminClient).close();
    }

    @Test
    @DisplayName("位移重置：重置至最新日志位移 Latest")
    @SuppressWarnings("unchecked")
    void testResetOffsetsToLatest() {
        String groupId = "order-group";
        String topic = "order.topic";
        mockTopicPartitions(topic, 2);

        TopicPartition tp0 = new TopicPartition(topic, 0);
        TopicPartition tp1 = new TopicPartition(topic, 1);

        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> listOffsetsMap = Map.of(
                tp0, new ListOffsetsResult.ListOffsetsResultInfo(500L, 1000L, Optional.empty()),
                tp1, new ListOffsetsResult.ListOffsetsResultInfo(800L, 1000L, Optional.empty())
        );

        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        when(listOffsetsResult.all()).thenReturn(KafkaFuture.completedFuture(listOffsetsMap));
        when(adminClient.listOffsets(anyMap())).thenReturn(listOffsetsResult);

        AlterConsumerGroupOffsetsResult alterResult = mock(AlterConsumerGroupOffsetsResult.class);
        when(alterResult.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(adminClient.alterConsumerGroupOffsets(eq(groupId), anyMap())).thenReturn(alterResult);

        Map<TopicPartition, OffsetAndMetadata> result = offsetManager.resetOffsetsToLatest(groupId, topic);

        assertThat(result).hasSize(2);
        assertThat(result.get(tp0).offset()).isEqualTo(500L);
        assertThat(result.get(tp1).offset()).isEqualTo(800L);

        ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> captor = ArgumentCaptor.forClass(Map.class);
        verify(adminClient).alterConsumerGroupOffsets(eq(groupId), captor.capture());
        assertThat(captor.getValue().get(tp0).offset()).isEqualTo(500L);
        assertThat(captor.getValue().get(tp1).offset()).isEqualTo(800L);

        verify(adminClient).close();
    }

    @Test
    @DisplayName("位移回拨：按时间戳回退位移（含时间超限自动降级至 LEO 保护）")
    @SuppressWarnings("unchecked")
    void testResetOffsetsToTimestampWithFallback() {
        String groupId = "order-group";
        String topic = "order.topic";
        mockTopicPartitions(topic, 2);

        TopicPartition tp0 = new TopicPartition(topic, 0);
        TopicPartition tp1 = new TopicPartition(topic, 1);

        // 分区 0 找到匹配时间戳位移 150L，分区 1 对应时间戳超过最新消息返回 -1L
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> timestampMap = Map.of(
                tp0, new ListOffsetsResult.ListOffsetsResultInfo(150L, 1000L, Optional.empty()),
                tp1, new ListOffsetsResult.ListOffsetsResultInfo(-1L, -1L, Optional.empty())
        );
        ListOffsetsResult timestampResult = mock(ListOffsetsResult.class);
        when(timestampResult.all()).thenReturn(KafkaFuture.completedFuture(timestampMap));

        // 分区 1 降级查询 latest 结果为 600L
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latestFallbackMap = Map.of(
                tp1, new ListOffsetsResult.ListOffsetsResultInfo(600L, 2000L, Optional.empty())
        );
        ListOffsetsResult latestFallbackResult = mock(ListOffsetsResult.class);
        when(latestFallbackResult.all()).thenReturn(KafkaFuture.completedFuture(latestFallbackMap));

        when(adminClient.listOffsets(anyMap())).thenReturn(timestampResult, latestFallbackResult);

        AlterConsumerGroupOffsetsResult alterResult = mock(AlterConsumerGroupOffsetsResult.class);
        when(alterResult.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(adminClient.alterConsumerGroupOffsets(eq(groupId), anyMap())).thenReturn(alterResult);

        Instant targetInstant = Instant.now().minusSeconds(300);
        Map<TopicPartition, OffsetAndMetadata> result = offsetManager.resetOffsetsToTimestamp(groupId, topic, targetInstant);

        assertThat(result).hasSize(2);
        assertThat(result.get(tp0).offset()).isEqualTo(150L);
        assertThat(result.get(tp1).offset()).isEqualTo(600L); // 自动弹性降级为 LEO 600L，杜绝 -1L 脏位移

        verify(adminClient).close();
    }

    @Test
    @DisplayName("实时 Lag 积压探测：多分区聚合计算与未提交分区保底")
    void testGetConsumerGroupLag() {
        String groupId = "order-group";
        String topic = "order.topic";
        mockTopicPartitions(topic, 3);

        TopicPartition tp0 = new TopicPartition(topic, 0);
        TopicPartition tp1 = new TopicPartition(topic, 1);
        TopicPartition tp2 = new TopicPartition(topic, 2);

        // 1. 各分区 LEO: 0=100, 1=200, 2=50
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latestOffsets = Map.of(
                tp0, new ListOffsetsResult.ListOffsetsResultInfo(100L, 1000L, Optional.empty()),
                tp1, new ListOffsetsResult.ListOffsetsResultInfo(200L, 1000L, Optional.empty()),
                tp2, new ListOffsetsResult.ListOffsetsResultInfo(50L, 1000L, Optional.empty())
        );
        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        when(listOffsetsResult.all()).thenReturn(KafkaFuture.completedFuture(latestOffsets));
        when(adminClient.listOffsets(anyMap())).thenReturn(listOffsetsResult);

        // 2. 消费组已提交位移：tp0 已消费 80，tp1 已消费 200 (lag=0)，tp2 尚未提交 (null)
        Map<TopicPartition, OffsetAndMetadata> committedOffsets = Map.of(
                tp0, new OffsetAndMetadata(80L),
                tp1, new OffsetAndMetadata(200L)
        );
        ListConsumerGroupOffsetsResult groupResult = mock(ListConsumerGroupOffsetsResult.class);
        when(groupResult.partitionsToOffsetAndMetadata()).thenReturn(KafkaFuture.completedFuture(committedOffsets));
        when(adminClient.listConsumerGroupOffsets(groupId)).thenReturn(groupResult);

        ConsumerGroupLag lagInfo = offsetManager.getConsumerGroupLag(groupId, topic);

        assertThat(lagInfo).isNotNull();
        assertThat(lagInfo.groupId()).isEqualTo(groupId);
        assertThat(lagInfo.topic()).isEqualTo(topic);
        // lag 计算：tp0 = 100-80 = 20; tp1 = 200-200 = 0; tp2 = 50-0 = 50; totalLag = 20 + 0 + 50 = 70
        assertThat(lagInfo.totalLag()).isEqualTo(70L);
        assertThat(lagInfo.partitionLags()).hasSize(3);

        ConsumerGroupLag.PartitionLag p0 = lagInfo.partitionLags().get(0);
        assertThat(p0.partition()).isEqualTo(0);
        assertThat(p0.committedOffset()).isEqualTo(80L);
        assertThat(p0.logEndOffset()).isEqualTo(100L);
        assertThat(p0.lag()).isEqualTo(20L);

        ConsumerGroupLag.PartitionLag p1 = lagInfo.partitionLags().get(1);
        assertThat(p1.partition()).isEqualTo(1);
        assertThat(p1.committedOffset()).isEqualTo(200L);
        assertThat(p1.logEndOffset()).isEqualTo(200L);
        assertThat(p1.lag()).isEqualTo(0L);

        ConsumerGroupLag.PartitionLag p2 = lagInfo.partitionLags().get(2);
        assertThat(p2.partition()).isEqualTo(2);
        assertThat(p2.committedOffset()).isEqualTo(-1L);
        assertThat(p2.logEndOffset()).isEqualTo(50L);
        assertThat(p2.lag()).isEqualTo(50L);

        verify(adminClient).close();
    }

    @Test
    @DisplayName("实时 Lag 积压探测：提交位移大于 LEO 时保底为 0（防负数防御）")
    void testGetConsumerGroupLagNegativeDefense() {
        String groupId = "order-group";
        String topic = "order.topic";
        mockTopicPartitions(topic, 1);

        TopicPartition tp0 = new TopicPartition(topic, 0);

        // LEO = 100L
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latestOffsets = Map.of(
                tp0, new ListOffsetsResult.ListOffsetsResultInfo(100L, 1000L, Optional.empty())
        );
        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        when(listOffsetsResult.all()).thenReturn(KafkaFuture.completedFuture(latestOffsets));
        when(adminClient.listOffsets(anyMap())).thenReturn(listOffsetsResult);

        // 提交位移异常偏大为 120L (如脏数据或历史日志清理截断)
        Map<TopicPartition, OffsetAndMetadata> committedOffsets = Map.of(
                tp0, new OffsetAndMetadata(120L)
        );
        ListConsumerGroupOffsetsResult groupResult = mock(ListConsumerGroupOffsetsResult.class);
        when(groupResult.partitionsToOffsetAndMetadata()).thenReturn(KafkaFuture.completedFuture(committedOffsets));
        when(adminClient.listConsumerGroupOffsets(groupId)).thenReturn(groupResult);

        ConsumerGroupLag lagInfo = offsetManager.getConsumerGroupLag(groupId, topic);

        assertThat(lagInfo.totalLag()).isEqualTo(0L);
        assertThat(lagInfo.partitionLags().get(0).lag()).isEqualTo(0L);

        verify(adminClient).close();
    }

    @Test
    @DisplayName("因果异常体系：底层 AdminClient 异常转译为 MessageDeliveryException 并保留原始 cause")
    void testCausalExceptionHandling() {
        String groupId = "order-group";
        String topic = "unknown.topic";

        DescribeTopicsResult descResult = mock(DescribeTopicsResult.class);
        KafkaFuture<Map<String, TopicDescription>> failedFuture = mock(KafkaFuture.class);
        when(descResult.allTopicNames()).thenReturn(failedFuture);
        try {
            when(failedFuture.get(anyLong(), any())).thenThrow(new ExecutionException("Topic not found", new RuntimeException("Broker error")));
        } catch (Exception ignored) {
        }
        when(adminClient.describeTopics(Collections.singletonList(topic))).thenReturn(descResult);

        assertThatThrownBy(() -> offsetManager.getConsumerGroupLag(groupId, topic))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("查询消费组积压 Lag 失败")
                .hasCauseInstanceOf(ExecutionException.class);

        // 验证发生异常时物理资源依然被确定性释放
        verify(adminClient).close();
    }

    // =========================================================================
    // 辅助插桩方法
    // =========================================================================

    private void mockTopicPartitions(String topic, int partitionCount) {
        List<TopicPartitionInfo> partitionInfos = new ArrayList<>();
        Node leader = new Node(1, "localhost", 9092);
        List<Node> nodes = List.of(leader);
        for (int i = 0; i < partitionCount; i++) {
            partitionInfos.add(new TopicPartitionInfo(i, leader, nodes, nodes));
        }

        TopicDescription description = new TopicDescription(topic, false, partitionInfos);
        DescribeTopicsResult describeTopicsResult = mock(DescribeTopicsResult.class);
        when(describeTopicsResult.allTopicNames())
                .thenReturn(KafkaFuture.completedFuture(Map.of(topic, description)));
        when(adminClient.describeTopics(Collections.singletonList(topic))).thenReturn(describeTopicsResult);
    }
}
