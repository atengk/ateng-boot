package io.github.atengk.boot.ai.graph;

import io.github.atengk.boot.ai.checkpoint.MemoryCheckpointSaver;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AtengAiGraphProperties;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 认知状态图人机协作门面 GraphResumeTemplate 单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class GraphResumeTemplateTest {

    static class OrderState extends BaseGraphState {
        public static final TypedKey<BigDecimal> AMOUNT = TypedKey.of("amount", BigDecimal.class);
        public static final TypedKey<Boolean> IS_HIGH_RISK = TypedKey.of("isHighRisk", Boolean.class);
        public static final TypedKey<String> DECISION = TypedKey.of("decision", String.class);

        public OrderState(Map<String, Object> initData) {
            super(initData);
        }
    }

    static class MockOrderWorkflow extends GraphWorkflow<OrderState> {

        public MockOrderWorkflow() {
            super("orderWorkflow", OrderState::new);
        }

        @Override
        protected void initGraph(StateGraph<OrderState> graph) throws GraphStateException {
            graph.addNode("riskCheck", node_async(state -> {
                BigDecimal amount = state.get(OrderState.AMOUNT, BigDecimal.ZERO);
                return Map.of("isHighRisk", amount.compareTo(new BigDecimal("1000")) > 0);
            }));

            graph.addNode("humanApproval", node_async(state -> {
                Object approval = state.data().get(TypedKey.APPROVAL_DATA.name());
                String decision = "PASSED".equals(approval) ? "ORDER_APPROVED" : "ORDER_REJECTED";
                return Map.of("decision", decision);
            }));

            graph.addEdge(START, "riskCheck");
            graph.addConditionalEdges("riskCheck", edge_async(state -> {
                Boolean highRisk = state.get(OrderState.IS_HIGH_RISK, false);
                return Boolean.TRUE.equals(highRisk) ? "humanApproval" : END;
            }), Map.of(
                    "humanApproval", "humanApproval",
                    END, END
            ));

            graph.addEdge("humanApproval", END);
        }

        @Override
        protected List<String> interruptBeforeNodes() {
            return List.of("humanApproval");
        }
    }

    private MockOrderWorkflow workflow;
    private MemoryCheckpointSaver checkpointSaver;
    private GraphWorkflowRegistry registry;
    private GraphResumeTemplate resumeTemplate;

    @BeforeEach
    void setUp() throws Exception {
        checkpointSaver = new MemoryCheckpointSaver();
        workflow = new MockOrderWorkflow();
        workflow.setCheckpointSaver(checkpointSaver);
        workflow.afterPropertiesSet();

        registry = new GraphWorkflowRegistry(null);
        registry.register(workflow);

        resumeTemplate = new GraphResumeTemplate(
                registry,
                checkpointSaver,
                new AtengAiGraphProperties(),
                null,
                null
        );
    }

    @Test
    @DisplayName("测试同步审批恢复流转至终态 COMPLETED")
    void testResumeSuccess() {
        String threadId = "tx-sync-100";
        OrderState initState = new OrderState(Map.of());
        initState.set(OrderState.AMOUNT, new BigDecimal("2000"));

        StateSnapshotVo startSnapshot = workflow.start(threadId, initState);
        assertThat(startSnapshot.status()).isEqualTo(WorkflowStatus.SUSPENDED);

        StateSnapshotVo resumedSnapshot = resumeTemplate.resume(threadId, "PASSED");
        assertThat(resumedSnapshot).isNotNull();
        assertThat(resumedSnapshot.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(resumedSnapshot.state()).containsEntry("decision", "ORDER_APPROVED");
    }

    @Test
    @DisplayName("CAS 状态机防伪：重复审批恢复抛出 AI_GRAPH_ALREADY_RESUMED")
    void testDoubleResumeDefense() {
        String threadId = "tx-double-200";
        OrderState initState = new OrderState(Map.of());
        initState.set(OrderState.AMOUNT, new BigDecimal("3000"));

        workflow.start(threadId, initState);

        // 第一次恢复成功
        resumeTemplate.resume(threadId, "PASSED");

        // 第二次重复恢复被 CAS 强防卫拦截
        assertThatThrownBy(() -> resumeTemplate.resume(threadId, "PASSED"))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode())
                        .isEqualTo(AiResultCode.AI_GRAPH_ALREADY_RESUMED));
    }

    @Test
    @DisplayName("工单主动撤销：cancel 标记 CANCELLED 并拦截后续审批恢复")
    void testCancelAndPreventResume() {
        String threadId = "tx-cancel-300";
        OrderState initState = new OrderState(Map.of());
        initState.set(OrderState.AMOUNT, new BigDecimal("5000"));

        workflow.start(threadId, initState);

        // 主动撤销
        StateSnapshotVo cancelSnapshot = resumeTemplate.cancel(threadId, "客户主动取消风控审核");
        assertThat(cancelSnapshot.status()).isEqualTo(WorkflowStatus.CANCELLED);
        assertThat(cancelSnapshot.state()).containsEntry("cancelReason", "客户主动取消风控审核");

        // 尝试恢复已撤销工单：抛出 AI_GRAPH_ALREADY_CANCELLED
        assertThatThrownBy(() -> resumeTemplate.resume(threadId, "PASSED"))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode())
                        .isEqualTo(AiResultCode.AI_GRAPH_ALREADY_CANCELLED));
    }

    @Test
    @DisplayName("异步恢复 resumeAsync：四维上下文透传与异步调度")
    void testResumeAsync() throws ExecutionException, InterruptedException {
        String threadId = "tx-async-400";
        OrderState initState = new OrderState(Map.of());
        initState.set(OrderState.AMOUNT, new BigDecimal("6000"));

        workflow.start(threadId, initState);

        MDC.put("traceId", "trace-resume-async-400");
        try {
            CompletableFuture<StateSnapshotVo> future = resumeTemplate.resumeAsync(threadId, "PASSED");
            StateSnapshotVo resultVo = future.get();

            assertThat(resultVo).isNotNull();
            assertThat(resultVo.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(resultVo.state()).containsEntry("decision", "ORDER_APPROVED");
        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("恢复不存在的会话标识：抛出 AI_GRAPH_SNAPSHOT_NOT_FOUND")
    void testResumeNonExistentThread() {
        assertThatThrownBy(() -> resumeTemplate.resume("non-exist-thread-999", "PASSED"))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode())
                        .isEqualTo(AiResultCode.AI_GRAPH_SNAPSHOT_NOT_FOUND));
    }
}
