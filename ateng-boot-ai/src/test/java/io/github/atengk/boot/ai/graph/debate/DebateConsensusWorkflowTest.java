package io.github.atengk.boot.ai.graph.debate;

import io.github.atengk.boot.ai.checkpoint.MemoryCheckpointSaver;
import io.github.atengk.boot.ai.checkpoint.RedisCheckpointSaver;
import io.github.atengk.boot.ai.graph.GraphResumeTemplate;
import io.github.atengk.boot.ai.graph.GraphWorkflowRegistry;
import io.github.atengk.boot.ai.graph.StateSnapshotVo;
import io.github.atengk.boot.ai.graph.WorkflowStatus;
import io.github.atengk.boot.ai.properties.AtengAiGraphProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 对抗式多智能体辩论与共识裁决工作流核心单元测试
 *
 * @author Ateng
 * @since 2026-11-06
 */
class DebateConsensusWorkflowTest {

    private MemoryCheckpointSaver checkpointSaver;

    @BeforeEach
    void setUp() {
        checkpointSaver = new MemoryCheckpointSaver();
    }

    /**
     * 测试两方辩论：第 2 轮达成共识
     */
    static class TwoRoundDebateWorkflow extends DebateConsensusWorkflow<DebateState> {

        public TwoRoundDebateWorkflow() {
            super("twoRoundDebateWorkflow", DebateState::new);
        }

        @Override
        protected List<DebateParticipant<DebateState>> registerDebaters() {
            return List.of(
                    DebateParticipant.of("proposer", "AFFIRMATIVE", "方案发起人", (state, round) -> {
                        if (round == 1) {
                            return Map.of("proposal", "采用纯内存缓存方案，提升响应速度", "readinessScore", 60);
                        } else {
                            return Map.of("proposal", "补充持久化与 Redis 集群降级，兼顾高可用", "readinessScore", 90);
                        }
                    }),
                    DebateParticipant.of("critic", "NEGATIVE", "架构质疑人", (state, round) -> {
                        int score = (int) state.data().getOrDefault("readinessScore", 0);
                        if (score < 80) {
                            return Map.of("critique", "纯内存缓存存在单点故障与丢数据风险，驳回", "criticApproved", false);
                        } else {
                            return Map.of("critique", "方案已补充集群与持久化，消除了单点故障，同意放行", "criticApproved", true);
                        }
                    })
            );
        }

        @Override
        protected boolean checkConsensus(DebateState state) {
            return Boolean.TRUE.equals(state.data().get("criticApproved"));
        }

        @Override
        protected Map<String, Object> adjudicate(DebateState state) {
            return Map.of(
                    DebateState.FINAL_VERDICT.name(), "APPROVED",
                    DebateState.CONSENSUS_SUMMARY.name(), "经过 2 轮博弈，双方对集群高可用架构达成一致意见"
            );
        }
    }

    /**
     * 死循环对抗辩论：双方永不妥协
     */
    static class DeadlockDebateWorkflow extends DebateConsensusWorkflow<DebateState> {

        public DeadlockDebateWorkflow() {
            super("deadlockDebateWorkflow", DebateState::new);
        }

        @Override
        protected int maxDebateTurns() {
            return 3; // 设置硬看门狗上限为 3 轮
        }

        @Override
        protected List<DebateParticipant<DebateState>> registerDebaters() {
            return List.of(
                    DebateParticipant.of("teamA", "PROPOSER", (state, round) -> "观点 A: 必须坚持微服务架构 (轮次 " + round + ")"),
                    DebateParticipant.of("teamB", "OPPONENT", (state, round) -> "观点 B: 必须坚持单体极速架构 (轮次 " + round + ")")
            );
        }

        @Override
        protected boolean checkConsensus(DebateState state) {
            return false; // 永远无法达成共识
        }

        @Override
        protected Map<String, Object> adjudicate(DebateState state) {
            return Map.of();
        }
    }

    @Test
    @DisplayName("端到端验证多轮对抗博弈：第 2 轮修正方案后顺利达成共识并完成裁决")
    void testConsensusReachedInSecondRound() {
        TwoRoundDebateWorkflow workflow = new TwoRoundDebateWorkflow();
        workflow.setCheckpointSaver(checkpointSaver);

        DebateState initState = new DebateState(Map.of("topic", "高并发订单系统缓存架构设计"));
        StateSnapshotVo snapshot = workflow.start("debate-thread-001", initState);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(snapshot.state()).containsEntry(DebateState.CONSENSUS_REACHED.name(), true);
        assertThat(snapshot.state()).containsEntry(DebateState.WATCHDOG_TRIPPED.name(), false);
        assertThat(snapshot.state()).containsEntry(DebateState.ROUND_COUNT.name(), 2);
        assertThat(snapshot.state()).containsEntry(DebateState.FINAL_VERDICT.name(), "APPROVED");
        assertThat(snapshot.state()).containsEntry("criticApproved", true);

        // 验证历史发言记录 (共 2 轮，每轮 2 位辩手发言，共 4 条记录)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) snapshot.state().get(DebateState.DEBATE_HISTORY.name());
        assertThat(history).hasSize(4);
    }

    @Test
    @DisplayName("验证死循环对抗辩论在达到 maxDebateTurns 上限时触发看门狗强制仲裁熔断")
    void testWatchdogTrippedOnDeadlockDebate() {
        DeadlockDebateWorkflow workflow = new DeadlockDebateWorkflow();
        workflow.setCheckpointSaver(checkpointSaver);

        DebateState initState = new DebateState(Map.of("topic", "架构风格死锁之辩"));
        StateSnapshotVo snapshot = workflow.start("debate-thread-002", initState);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(snapshot.state()).containsEntry(DebateState.CONSENSUS_REACHED.name(), false);
        assertThat(snapshot.state()).containsEntry(DebateState.WATCHDOG_TRIPPED.name(), true);
        assertThat(snapshot.state()).containsEntry(DebateState.FINAL_VERDICT.name(), "FORCED_ARBITRATION");
        assertThat(snapshot.state()).containsEntry(DebateState.ROUND_COUNT.name(), 3);

        String summary = (String) snapshot.state().get(DebateState.CONSENSUS_SUMMARY.name());
        assertThat(summary).contains("达到最大辩论轮次上限 (3 轮)");

        // 验证发言记录为 3 轮 * 2 人 = 6 条
        @SuppressWarnings("unchecked")
        List<?> history = (List<?>) snapshot.state().get(DebateState.DEBATE_HISTORY.name());
        assertThat(history).hasSize(6);
    }

    @Test
    @DisplayName("验证通过 GraphResumeTemplate 门面进行快照检索")
    void testGraphResumeTemplateIntegration() throws Exception {
        TwoRoundDebateWorkflow workflow = new TwoRoundDebateWorkflow();
        workflow.setCheckpointSaver(checkpointSaver);
        workflow.afterPropertiesSet();

        GraphWorkflowRegistry registry = new GraphWorkflowRegistry(null);
        registry.register(workflow);

        GraphResumeTemplate template = new GraphResumeTemplate(
                registry,
                checkpointSaver,
                new AtengAiGraphProperties(),
                null,
                null
        );

        DebateState initState = new DebateState(Map.of("topic", "快照检索验证辩题"));
        StateSnapshotVo startVo = workflow.start("tpl-debate-003", initState);
        assertThat(startVo.status()).isEqualTo(WorkflowStatus.COMPLETED);

        // 通过 template 检索快照
        Optional<StateSnapshotVo> queryVo = template.getSnapshot("tpl-debate-003");
        assertThat(queryVo).isPresent();
        assertThat(queryVo.get().state()).containsEntry(DebateState.CONSENSUS_REACHED.name(), true);
    }

    @Test
    @DisplayName("验证辩手非法元数据注册校验 (空列表、重复名称、系统保留字)")
    void testValidationFailures() {
        // 空辩手列表
        assertThatThrownBy(() -> {
            DebateConsensusWorkflow<DebateState> emptyWorkflow = new DebateConsensusWorkflow<>("empty", DebateState::new) {
                @Override
                protected List<DebateParticipant<DebateState>> registerDebaters() {
                    return List.of();
                }

                @Override
                protected boolean checkConsensus(DebateState state) {
                    return true;
                }

                @Override
                protected Map<String, Object> adjudicate(DebateState state) {
                    return Map.of();
                }
            };
            emptyWorkflow.getDebaters();
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("必须至少注册一名辩论参与方");

        // 重复辩手名称
        assertThatThrownBy(() -> {
            DebateConsensusWorkflow<DebateState> dupWorkflow = new DebateConsensusWorkflow<>("dup", DebateState::new) {
                @Override
                protected List<DebateParticipant<DebateState>> registerDebaters() {
                    return List.of(
                            DebateParticipant.of("proposer", "AFFIRMATIVE", (s, r) -> "a"),
                            DebateParticipant.of("proposer", "NEGATIVE", (s, r) -> "b")
                    );
                }

                @Override
                protected boolean checkConsensus(DebateState state) {
                    return true;
                }

                @Override
                protected Map<String, Object> adjudicate(DebateState state) {
                    return Map.of();
                }
            };
            dupWorkflow.afterPropertiesSet();
        }).isInstanceOf(IllegalStateException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);

        // 系统保留字标识
        assertThatThrownBy(() -> {
            DebateConsensusWorkflow<DebateState> reservedWorkflow = new DebateConsensusWorkflow<>("reserved", DebateState::new) {
                @Override
                protected List<DebateParticipant<DebateState>> registerDebaters() {
                    return List.of(DebateParticipant.of("arbiter", "AFFIRMATIVE", (s, r) -> "a"));
                }

                @Override
                protected boolean checkConsensus(DebateState state) {
                    return true;
                }

                @Override
                protected Map<String, Object> adjudicate(DebateState state) {
                    return Map.of();
                }
            };
            reservedWorkflow.afterPropertiesSet();
        }).isInstanceOf(IllegalStateException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("验证辩论工作流与分布式 RedisCheckpointSaver 全链路集成与快照序列化持久化")
    void testDebateExecutionWithRedisCheckpointSaver() {
        RedisCheckpointSaver redisSaver = createInMemoryRedisSaver();
        TwoRoundDebateWorkflow workflow = new TwoRoundDebateWorkflow();
        workflow.setCheckpointSaver(redisSaver);

        DebateState initState = new DebateState(Map.of("topic", "Redis检查点持久化验证辩题"));
        StateSnapshotVo snapshot = workflow.start("test-redis-debate-001", initState);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(snapshot.state()).containsEntry(DebateState.CONSENSUS_REACHED.name(), true);
        assertThat(snapshot.state()).containsEntry(DebateState.FINAL_VERDICT.name(), "APPROVED");

        // 验证可通过 threadId 成功从 RedisCheckpointSaver 读取已持久化的快照
        Optional<StateSnapshotVo> queried = workflow.getSnapshot("test-redis-debate-001");
        assertThat(queried).isPresent();
        assertThat(queried.get().status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(queried.get().state()).containsEntry(DebateState.CONSENSUS_REACHED.name(), true);

        @SuppressWarnings("unchecked")
        List<?> history = (List<?>) queried.get().state().get(DebateState.DEBATE_HISTORY.name());
        assertThat(history).hasSize(4);
    }

    private RedisCheckpointSaver createInMemoryRedisSaver() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> listOperations = mock(ListOperations.class);

        Map<String, String> store = new ConcurrentHashMap<>();
        Map<String, List<String>> listStore = new ConcurrentHashMap<>();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String val = invocation.getArgument(1);
            store.put(key, val);
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return store.get(key);
        }).when(valueOperations).get(anyString());

        doAnswer(invocation -> {
            List<String> keys = invocation.getArgument(0);
            List<String> result = new ArrayList<>();
            for (String k : keys) {
                result.add(store.get(k));
            }
            return result;
        }).when(valueOperations).multiGet(anyList());

        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String val = invocation.getArgument(1);
            listStore.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(0, val);
            return 1L;
        }).when(listOperations).leftPush(anyString(), anyString());

        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            List<String> l = listStore.get(key);
            return (l != null && !l.isEmpty()) ? l.get(0) : null;
        }).when(listOperations).index(anyString(), eq(0L));

        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            List<String> l = listStore.get(key);
            return l != null ? new ArrayList<>(l) : Collections.emptyList();
        }).when(listOperations).range(anyString(), eq(0L), eq(-1L));

        when(redisTemplate.hasKey(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return listStore.containsKey(key) || store.containsKey(key);
        });

        return new RedisCheckpointSaver(redisTemplate, "ateng:ai:test:checkpoint:", Duration.ofDays(1));
    }
}
