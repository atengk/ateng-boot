package io.github.atengk.boot.ai.graph;

import io.github.atengk.boot.ai.checkpoint.MemoryCheckpointSaver;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 认知状态图工作流 GraphWorkflow 与生命周期中断单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class GraphWorkflowLifecycleTest {

    static class OrderState extends BaseGraphState {
        public static final TypedKey<BigDecimal> AMOUNT = TypedKey.of("amount", BigDecimal.class);
        public static final TypedKey<Boolean> IS_HIGH_RISK = TypedKey.of("isHighRisk", Boolean.class);
        public static final TypedKey<String> DECISION = TypedKey.of("decision", String.class);

        public OrderState(Map<String, Object> initData) {
            super(initData);
        }
    }

    static class OrderReviewWorkflow extends GraphWorkflow<OrderState> {

        public OrderReviewWorkflow() {
            super("orderReview", OrderState::new);
        }

        @Override
        protected void initGraph(StateGraph<OrderState> graph) throws GraphStateException {
            // 节点 1：风控初审
            graph.addNode("riskAnalysis", node_async(state -> {
                BigDecimal amount = state.get(OrderState.AMOUNT, BigDecimal.ZERO);
                boolean highRisk = amount.compareTo(new BigDecimal("1000")) > 0;
                return Map.of("isHighRisk", highRisk);
            }));

            // 节点 2：人工审批（敏感节点）
            graph.addNode("humanReview", node_async(state -> {
                Object approval = state.data().get(TypedKey.APPROVAL_DATA.name());
                String decision = "APPROVED".equals(approval) ? "MANUAL_PASSED" : "MANUAL_REJECTED";
                return Map.of("decision", decision);
            }));

            // 节点 3：自动秒级放行
            graph.addNode("autoApprove", node_async(state -> Map.of("decision", "AUTO_PASSED")));

            // 边连接
            graph.addEdge(START, "riskAnalysis");

            // 条件分支
            graph.addConditionalEdges("riskAnalysis", edge_async(state -> {
                Boolean highRisk = state.get(OrderState.IS_HIGH_RISK, false);
                return Boolean.TRUE.equals(highRisk) ? "humanReview" : "autoApprove";
            }), Map.of(
                    "humanReview", "humanReview",
                    "autoApprove", "autoApprove"
            ));

            graph.addEdge("humanReview", END);
            graph.addEdge("autoApprove", END);
        }

        @Override
        protected List<String> interruptBeforeNodes() {
            return List.of("humanReview");
        }
    }

    private OrderReviewWorkflow workflow;
    private MemoryCheckpointSaver checkpointSaver;

    @BeforeEach
    void setUp() throws Exception {
        checkpointSaver = new MemoryCheckpointSaver();
        workflow = new OrderReviewWorkflow();
        workflow.setCheckpointSaver(checkpointSaver);
        workflow.afterPropertiesSet();
    }

    @Test
    @DisplayName("低风险订单：自动秒级放行直接流向终态 COMPLETED")
    void testLowRiskOrderAutoApprove() {
        OrderState initState = new OrderState(Map.of());
        initState.set(OrderState.AMOUNT, new BigDecimal("500"));

        StateSnapshotVo snapshot = workflow.start(initState);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(snapshot.isCompleted()).isTrue();
        assertThat(snapshot.isSuspended()).isFalse();
        assertThat(snapshot.state()).containsEntry("decision", "AUTO_PASSED");
    }

    @Test
    @DisplayName("高风险订单：流向敏感节点并触发 interruptBefore 挂起，返回 SUSPENDED 快照")
    void testHighRiskOrderSuspension() {
        String threadId = "order-tx-999";
        OrderState initState = new OrderState(Map.of());
        initState.set(OrderState.AMOUNT, new BigDecimal("8888"));

        StateSnapshotVo snapshot = workflow.start(threadId, initState);

        // 断言中断挂起
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.threadId()).isEqualTo(threadId);
        assertThat(snapshot.status()).isEqualTo(WorkflowStatus.SUSPENDED);
        assertThat(snapshot.isSuspended()).isTrue();
        assertThat(snapshot.currentNode()).isEqualTo("riskAnalysis");
        assertThat(snapshot.nextNode()).isEqualTo("humanReview");

        // 验证快照已写入 Checkpoint 存储器并可查询
        Optional<StateSnapshotVo> queryOpt = workflow.getSnapshot(threadId);
        assertThat(queryOpt).isPresent();
        assertThat(queryOpt.get().status()).isEqualTo(WorkflowStatus.SUSPENDED);
        assertThat(queryOpt.get().nextNode()).isEqualTo("humanReview");
    }

    @Test
    @DisplayName("挂起流程人工审批恢复：传入审批数据推进至终态 COMPLETED")
    void testResumeSuspendedWorkflow() {
        String threadId = "order-tx-888";
        OrderState initState = new OrderState(Map.of());
        initState.set(OrderState.AMOUNT, new BigDecimal("6000"));

        // 1. 触发挂起
        StateSnapshotVo suspendedSnapshot = workflow.start(threadId, initState);
        assertThat(suspendedSnapshot.status()).isEqualTo(WorkflowStatus.SUSPENDED);

        // 2. 模拟人工审核通过并恢复
        StateSnapshotVo resumedSnapshot = workflow.resume(threadId, Map.of(TypedKey.APPROVAL_DATA.name(), "APPROVED"));

        // 3. 断言执行推进至终态
        assertThat(resumedSnapshot).isNotNull();
        assertThat(resumedSnapshot.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(resumedSnapshot.isCompleted()).isTrue();
        assertThat(resumedSnapshot.state()).containsEntry("decision", "MANUAL_PASSED");
    }

    @Test
    @DisplayName("测试 GraphWorkflowRegistry 多图索引与动态路由")
    void testWorkflowRegistry() {
        GraphWorkflowRegistry registry = new GraphWorkflowRegistry(null);
        registry.register(workflow);

        assertThat(registry.contains("orderReview")).isTrue();
        assertThat(registry.find("orderReview")).isPresent();

        OrderReviewWorkflow fetched = (OrderReviewWorkflow) registry.getRequired("orderReview");
        assertThat(fetched).isSameAs(workflow);

        GraphWorkflow<OrderState> typedWf = registry.getRequired("orderReview", OrderState.class);
        assertThat(typedWf).isSameAs(workflow);

        assertThatThrownBy(() -> registry.getRequired("nonExist"))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode())
                        .isEqualTo(AiResultCode.AI_GRAPH_WORKFLOW_NOT_FOUND));
    }

    @Test
    @DisplayName("测试状态更新原语 updateState：就地持久化状态字典且不触发后续节点推进")
    void testUpdateStateSnapshot() {
        String threadId = "order-tx-update-state-999";
        OrderState initState = new OrderState(Map.of());
        initState.set(OrderState.AMOUNT, new BigDecimal("8888"));

        // 1. 触发挂起
        workflow.start(threadId, initState);

        // 2. 局部更新状态字典
        workflow.updateState(threadId, Map.of(
                TypedKey.STATUS.name(), WorkflowStatus.CANCELLED.name(),
                "cancelReason", "系统风控阻断"
        ));

        // 3. 验证快照读取
        Optional<StateSnapshotVo> snapshotOpt = workflow.getSnapshot(threadId);
        assertThat(snapshotOpt).isPresent();
        assertThat(snapshotOpt.get().status()).isEqualTo(WorkflowStatus.CANCELLED);
        assertThat(snapshotOpt.get().state()).containsEntry("cancelReason", "系统风控阻断");
    }
}
