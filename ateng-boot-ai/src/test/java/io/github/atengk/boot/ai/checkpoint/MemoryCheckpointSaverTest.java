package io.github.atengk.boot.ai.checkpoint;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内存检查点存储器 MemoryCheckpointSaver 单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class MemoryCheckpointSaverTest {

    @Test
    @DisplayName("测试保存新检查点与根据线程获取最新检查点")
    void testPutAndGetLatest() throws Exception {
        MemoryCheckpointSaver saver = new MemoryCheckpointSaver();
        String threadId = "test-thread-001";
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        assertThat(saver.exists(threadId)).isFalse();
        assertThat(saver.getLatest(threadId)).isEmpty();

        // 1. 存入第一个检查点
        Checkpoint cp1 = Checkpoint.builder()
                .id("cp-01")
                .nodeId("nodeA")
                .nextNodeId("nodeB")
                .state(Map.of("step", 1))
                .build();

        RunnableConfig returnConfig1 = saver.put(config, cp1);
        assertThat(returnConfig1.checkPointId()).contains("cp-01");
        assertThat(saver.exists(threadId)).isTrue();

        Optional<Checkpoint> latestOpt = saver.getLatest(threadId);
        assertThat(latestOpt).isPresent();
        assertThat(latestOpt.get().getId()).isEqualTo("cp-01");
        assertThat(latestOpt.get().getNodeId()).isEqualTo("nodeA");
        assertThat(latestOpt.get().getState()).containsEntry("step", 1);

        // 2. 存入第二个检查点
        Checkpoint cp2 = Checkpoint.builder()
                .id("cp-02")
                .nodeId("nodeB")
                .nextNodeId("nodeC")
                .state(Map.of("step", 2))
                .build();

        saver.put(config, cp2);
        Optional<Checkpoint> latestOpt2 = saver.getLatest(threadId);
        assertThat(latestOpt2).isPresent();
        assertThat(latestOpt2.get().getId()).isEqualTo("cp-02");
        assertThat(latestOpt2.get().getNodeId()).isEqualTo("nodeB");

        // 3. 验证 list 历史
        Collection<Checkpoint> list = saver.list(config);
        assertThat(list).hasSize(2);
        assertThat(list.stream().map(Checkpoint::getId).toList()).containsExactly("cp-02", "cp-01");
    }

    @Test
    @DisplayName("测试更新已有检查点与按 checkpointId 精准查询")
    void testUpdateExistingCheckpoint() throws Exception {
        MemoryCheckpointSaver saver = new MemoryCheckpointSaver();
        String threadId = "test-thread-002";
        RunnableConfig baseConfig = RunnableConfig.builder().threadId(threadId).build();

        Checkpoint cp = Checkpoint.builder()
                .id("cp-update-01")
                .nodeId("nodeX")
                .nextNodeId("nodeY")
                .state(Map.of("value", "initial"))
                .build();

        saver.put(baseConfig, cp);

        // 指定 checkPointId 更新
        RunnableConfig updateConfig = RunnableConfig.builder(baseConfig)
                .checkPointId("cp-update-01")
                .build();

        Checkpoint updatedCp = Checkpoint.builder()
                .id("cp-update-01")
                .nodeId("nodeX")
                .nextNodeId("nodeY")
                .state(Map.of("value", "updated"))
                .build();

        saver.put(updateConfig, updatedCp);

        Optional<Checkpoint> fetched = saver.get(updateConfig);
        assertThat(fetched).isPresent();
        assertThat(fetched.get().getState()).containsEntry("value", "updated");

        // 更新不存在的检查点抛出异常
        RunnableConfig nonExistConfig = RunnableConfig.builder(baseConfig)
                .checkPointId("non-exist")
                .build();
        assertThatThrownBy(() -> saver.put(nonExistConfig, cp))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("测试清空指定线程会话的检查点记录")
    void testClearThreadCheckpoints() throws Exception {
        MemoryCheckpointSaver saver = new MemoryCheckpointSaver();
        String threadId = "test-thread-003";
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        Checkpoint cp = Checkpoint.builder()
                .id("cp-003")
                .nodeId("nodeA")
                .nextNodeId("nodeB")
                .state(Map.of())
                .build();
        saver.put(config, cp);
        assertThat(saver.exists(threadId)).isTrue();

        saver.clear(threadId);
        assertThat(saver.exists(threadId)).isFalse();
        assertThat(saver.getLatest(threadId)).isEmpty();
        assertThat(saver.list(config)).isEmpty();
    }
}
