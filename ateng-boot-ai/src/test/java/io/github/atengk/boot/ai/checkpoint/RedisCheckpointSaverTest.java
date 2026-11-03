package io.github.atengk.boot.ai.checkpoint;

import io.github.atengk.boot.core.util.JsonUtil;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 分布式 RedisCheckpointSaver 单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class RedisCheckpointSaverTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ListOperations<String, String> listOperations;
    private RedisCheckpointSaver saver;

    private static final String KEY_PREFIX = "ateng:ai:graph:checkpoint:";
    private static final Duration TTL = Duration.ofDays(7);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        listOperations = mock(ListOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        saver = new RedisCheckpointSaver(redisTemplate, KEY_PREFIX, TTL);
    }

    @Test
    @DisplayName("测试保存检查点时写入数据键与历史列表并挂载 TTL")
    void testPutCheckpointWithTtl() throws Exception {
        String threadId = "thread-100";
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        Checkpoint checkpoint = Checkpoint.builder()
                .id("cp-100")
                .nodeId("reviewNode")
                .nextNodeId("approvalNode")
                .state(Map.of("amount", 5000))
                .build();

        RunnableConfig resultConfig = saver.put(config, checkpoint);
        assertThat(resultConfig.checkPointId()).contains("cp-100");

        // 验证 dataKey 写入并带有 TTL
        ArgumentCaptor<String> dataCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(KEY_PREFIX + "data:thread-100:cp-100"), dataCaptor.capture(), eq(TTL));
        String json = dataCaptor.getValue();
        assertThat(json).contains("cp-100");
        assertThat(json).contains("reviewNode");
        assertThat(json).doesNotContain("@class");

        // 验证 historyKey 写入列表与过期时间
        verify(listOperations).leftPush(KEY_PREFIX + "history:thread-100", "cp-100");
        verify(redisTemplate).expire(KEY_PREFIX + "history:thread-100", TTL);
    }

    @Test
    @DisplayName("测试查询最新检查点")
    void testGetLatestCheckpoint() {
        String threadId = "thread-200";
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        String historyKey = KEY_PREFIX + "history:thread-200";
        when(listOperations.index(historyKey, 0)).thenReturn("cp-200");

        CheckpointDto dto = new CheckpointDto("cp-200", "nodeA", "nodeB", Map.of("key", "val"), System.currentTimeMillis());
        when(valueOperations.get(KEY_PREFIX + "data:thread-200:cp-200")).thenReturn(JsonUtil.toJsonString(dto));

        Optional<Checkpoint> checkpointOpt = saver.get(config);
        assertThat(checkpointOpt).isPresent();
        Checkpoint cp = checkpointOpt.get();
        assertThat(cp.getId()).isEqualTo("cp-200");
        assertThat(cp.getNodeId()).isEqualTo("nodeA");
        assertThat(cp.getState()).containsEntry("key", "val");
    }

    @Test
    @DisplayName("测试根据 checkpointId 精准查询检查点")
    void testGetCheckpointById() {
        String threadId = "thread-300";
        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .checkPointId("cp-target")
                .build();

        CheckpointDto dto = new CheckpointDto("cp-target", "targetNode", "endNode", Map.of("score", 95), System.currentTimeMillis());
        when(valueOperations.get(KEY_PREFIX + "data:thread-300:cp-target")).thenReturn(JsonUtil.toJsonString(dto));

        Optional<Checkpoint> checkpointOpt = saver.get(config);
        assertThat(checkpointOpt).isPresent();
        assertThat(checkpointOpt.get().getId()).isEqualTo("cp-target");
        assertThat(checkpointOpt.get().getState()).containsEntry("score", 95);
    }

    @Test
    @DisplayName("测试列出全量历史检查点")
    void testListCheckpoints() {
        String threadId = "thread-400";
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        String historyKey = KEY_PREFIX + "history:thread-400";
        when(listOperations.range(historyKey, 0, -1)).thenReturn(List.of("cp-02", "cp-01"));

        CheckpointDto dto1 = new CheckpointDto("cp-01", "node1", null, Map.of(), System.currentTimeMillis());
        CheckpointDto dto2 = new CheckpointDto("cp-02", "node2", null, Map.of(), System.currentTimeMillis());

        when(valueOperations.get(KEY_PREFIX + "data:thread-400:cp-01")).thenReturn(JsonUtil.toJsonString(dto1));
        when(valueOperations.get(KEY_PREFIX + "data:thread-400:cp-02")).thenReturn(JsonUtil.toJsonString(dto2));

        Collection<Checkpoint> list = saver.list(config);
        assertThat(list).hasSize(2);
        assertThat(list.stream().map(Checkpoint::getId).toList()).containsExactly("cp-02", "cp-01");
    }

    @Test
    @DisplayName("测试检查点存在性判断与清空")
    void testExistsAndClear() {
        String threadId = "thread-500";
        String historyKey = KEY_PREFIX + "history:thread-500";

        when(redisTemplate.hasKey(historyKey)).thenReturn(Boolean.TRUE);
        assertThat(saver.exists(threadId)).isTrue();

        when(listOperations.range(historyKey, 0, -1)).thenReturn(List.of("cp-1", "cp-2"));

        saver.clear(threadId);

        verify(redisTemplate).delete(List.of(
                KEY_PREFIX + "data:thread-500:cp-1",
                KEY_PREFIX + "data:thread-500:cp-2"
        ));
        verify(redisTemplate).delete(historyKey);
    }
}
