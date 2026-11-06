package io.github.atengk.boot.ai.graph.pipeline;

import io.github.atengk.boot.ai.checkpoint.MemoryCheckpointSaver;
import io.github.atengk.boot.ai.graph.BaseGraphState;
import io.github.atengk.boot.ai.graph.GraphResumeTemplate;
import io.github.atengk.boot.ai.graph.GraphWorkflowRegistry;
import io.github.atengk.boot.ai.graph.LocalGraphLockManager;
import io.github.atengk.boot.ai.graph.StateSnapshotVo;
import io.github.atengk.boot.ai.graph.TypedKey;
import io.github.atengk.boot.ai.graph.WorkflowStatus;
import io.github.atengk.boot.ai.properties.AtengAiGraphProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 确定性顺序流水线认知状态图工作流核心单元测试
 *
 * @author Ateng
 * @since 2026-11-06
 */
class SequentialPipelineWorkflowTest {

    private MemoryCheckpointSaver checkpointSaver;

    @BeforeEach
    void setUp() {
        checkpointSaver = new MemoryCheckpointSaver();
    }

    /**
     * 测试用三阶段顺序流水线
     */
    static class OrderProcessingWorkflow extends SequentialPipelineWorkflow<PipelineState> {

        public OrderProcessingWorkflow() {
            super("orderProcessingWorkflow", PipelineState::new);
        }

        @Override
        protected List<PipelineStage<PipelineState>> registerStages() {
            return List.of(
                    PipelineStage.of("verification", state -> Map.of("verified", true, "stockReserved", true)),
                    PipelineStage.of("riskCheck",
                            state -> Map.of("riskLevel", "LOW"),
                            state -> Boolean.TRUE.equals(state.data().get("skipRiskCheck"))),
                    PipelineStage.of("fulfillment", state -> Map.of("trackingNumber", "EXPR-8888", "delivered", true))
            );
        }
    }

    /**
     * 人机协同中断挂起测试流水线
     */
    static class HitlPipelineWorkflow extends SequentialPipelineWorkflow<PipelineState> {

        public HitlPipelineWorkflow() {
            super("hitlPipelineWorkflow", PipelineState::new);
        }

        @Override
        protected List<PipelineStage<PipelineState>> registerStages() {
            return List.of(
                    PipelineStage.of("prep", state -> Map.of("prepared", true)),
                    PipelineStage.<PipelineState>builder()
                            .name("humanApproval")
                            .handler(state -> {
                                Object approval = state.data().get("approvalResult");
                                if (approval == null) {
                                    Object appData = state.data().get(TypedKey.APPROVAL_DATA.name());
                                    if (appData instanceof Map<?, ?> m) {
                                        approval = m.get("approvalResult");
                                    } else {
                                        approval = appData;
                                    }
                                }
                                if (!"APPROVED".equals(approval)) {
                                    throw new IllegalStateException("审批未通过: " + approval);
                                }
                                return Map.of("approved", true);
                            })
                            .interruptBefore(true)
                            .build(),
                    PipelineStage.of("finalize", state -> Map.of("finalized", true))
            );
        }
    }

    /**
     * 阶段抛出异常测试流水线 (快速失败)
     */
    static class FailFastPipelineWorkflow extends SequentialPipelineWorkflow<PipelineState> {

        public FailFastPipelineWorkflow() {
            super("failFastPipelineWorkflow", PipelineState::new);
        }

        @Override
        protected List<PipelineStage<PipelineState>> registerStages() {
            return List.of(
                    PipelineStage.of("step1", state -> Map.of("step1", "OK")),
                    PipelineStage.of("step2", state -> {
                        throw new RuntimeException("数据库写入超时模拟异常");
                    }),
                    PipelineStage.of("step3", state -> Map.of("step3", "SHOULD_NOT_RUN"))
            );
        }
    }

    /**
     * 阶段抛出异常测试流水线 (容错继续执行)
     */
    static class ContinueOnErrorPipelineWorkflow extends SequentialPipelineWorkflow<PipelineState> {

        public ContinueOnErrorPipelineWorkflow() {
            super("continueOnErrorPipelineWorkflow", PipelineState::new);
        }

        @Override
        protected List<PipelineStage<PipelineState>> registerStages() {
            return List.of(
                    PipelineStage.of("step1", state -> Map.of("step1", "OK")),
                    PipelineStage.<PipelineState>builder()
                            .name("step2")
                            .handler(state -> {
                                throw new RuntimeException("非关键日志上报异常");
                            })
                            .continueOnError(true)
                            .build(),
                    PipelineStage.of("step3", state -> Map.of("step3", "OK"))
            );
        }
    }

    @Test
    @DisplayName("验证所有流水线阶段按严格顺序逐一执行至终态 COMPLETED")
    void testAllStagesExecutedSequentially() {
        OrderProcessingWorkflow workflow = new OrderProcessingWorkflow();
        workflow.setCheckpointSaver(checkpointSaver);

        PipelineState initState = new PipelineState(Map.of("orderId", "ORD-1001"));

        StateSnapshotVo snapshot = workflow.start("test-thread-001", initState);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(snapshot.state()).containsEntry("verified", true);
        assertThat(snapshot.state()).containsEntry("riskLevel", "LOW");
        assertThat(snapshot.state()).containsEntry("trackingNumber", "EXPR-8888");

        // 验证已完成阶段记录
        @SuppressWarnings("unchecked")
        List<String> completed = (List<String>) snapshot.state().get(PipelineState.COMPLETED_STAGES.name());
        assertThat(completed).containsExactly("verification", "riskCheck", "fulfillment");

        // 验证产出字典
        @SuppressWarnings("unchecked")
        Map<String, Object> results = (Map<String, Object>) snapshot.state().get(PipelineState.STAGE_RESULTS.name());
        assertThat(results).containsKeys("verification", "riskCheck", "fulfillment");
    }

    @Test
    @DisplayName("验证满足 skipCondition 的阶段自动跳过且记录在 skippedStages 中")
    void testConditionalSkip() {
        OrderProcessingWorkflow workflow = new OrderProcessingWorkflow();
        workflow.setCheckpointSaver(checkpointSaver);

        PipelineState initState = new PipelineState(Map.of("orderId", "ORD-VIP-002", "skipRiskCheck", true));

        StateSnapshotVo snapshot = workflow.start("test-thread-002", initState);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(snapshot.state()).containsEntry("verified", true);
        assertThat(snapshot.state()).doesNotContainKey("riskLevel");
        assertThat(snapshot.state()).containsEntry("trackingNumber", "EXPR-8888");

        // 验证完成列表不含 riskCheck
        @SuppressWarnings("unchecked")
        List<String> completed = (List<String>) snapshot.state().get(PipelineState.COMPLETED_STAGES.name());
        assertThat(completed).containsExactly("verification", "fulfillment");

        // 验证跳过列表包含 riskCheck
        @SuppressWarnings("unchecked")
        List<String> skipped = (List<String>) snapshot.state().get(PipelineState.SKIPPED_STAGES.name());
        assertThat(skipped).containsExactly("riskCheck");
    }

    @Test
    @DisplayName("验证 interruptBefore 阶段前置挂起中断与断点注入数据续跑闭环")
    void testInterruptBeforeAndResume() {
        HitlPipelineWorkflow workflow = new HitlPipelineWorkflow();
        workflow.setCheckpointSaver(checkpointSaver);

        PipelineState initState = new PipelineState(Map.of("taskId", "TASK-APPROVAL-003"));

        // 1. 启动工作流，预期在 humanApproval 前挂起
        StateSnapshotVo suspendedSnapshot = workflow.start("test-thread-003", initState);
        assertThat(suspendedSnapshot.status()).isEqualTo(WorkflowStatus.SUSPENDED);
        assertThat(suspendedSnapshot.nextNode()).isEqualTo("humanApproval");
        assertThat(suspendedSnapshot.state()).containsEntry("prepared", true);
        assertThat(suspendedSnapshot.state()).doesNotContainKey("approved");

        // 2. 检查点已持久化，验证可查询到挂起快照
        Optional<StateSnapshotVo> querySnapshot = workflow.getSnapshot("test-thread-003");
        assertThat(querySnapshot).isPresent();
        assertThat(querySnapshot.get().status()).isEqualTo(WorkflowStatus.SUSPENDED);

        // 3. 恢复执行并注入审批结果 APPROVED
        StateSnapshotVo resumedSnapshot = workflow.resume("test-thread-003", Map.of("approvalResult", "APPROVED"));
        assertThat(resumedSnapshot.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(resumedSnapshot.state()).containsEntry("approved", true);
        assertThat(resumedSnapshot.state()).containsEntry("finalized", true);

        @SuppressWarnings("unchecked")
        List<String> completed = (List<String>) resumedSnapshot.state().get(PipelineState.COMPLETED_STAGES.name());
        assertThat(completed).containsExactly("prep", "humanApproval", "finalize");
    }

    @Test
    @DisplayName("验证通过 GraphResumeTemplate 门面实现 SequentialPipelineWorkflow 的快照检索与断点续跑")
    void testResumeViaGraphResumeTemplate() throws Exception {
        HitlPipelineWorkflow workflow = new HitlPipelineWorkflow();
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

        PipelineState initState = new PipelineState(Map.of("taskId", "TASK-TEMPLATE-001"));
        StateSnapshotVo startVo = workflow.start("tpl-thread-001", initState);
        assertThat(startVo.status()).isEqualTo(WorkflowStatus.SUSPENDED);

        // 验证通过 template 获取快照
        Optional<StateSnapshotVo> snapshotOpt = template.getSnapshot("tpl-thread-001");
        assertThat(snapshotOpt).isPresent();
        assertThat(snapshotOpt.get().nextNode()).isEqualTo("humanApproval");

        // 验证通过 template 续跑
        StateSnapshotVo resumeVo = template.resume("tpl-thread-001", Map.of("approvalResult", "APPROVED"));
        assertThat(resumeVo.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(resumeVo.state()).containsEntry("approved", true);
        assertThat(resumeVo.state()).containsEntry("finalized", true);
    }

    @Test
    @DisplayName("验证阶段执行异常时默认 Fail-Fast 路由至 FAILED 终态且后续阶段不执行")
    void testStageFailureFailFast() {
        FailFastPipelineWorkflow workflow = new FailFastPipelineWorkflow();
        workflow.setCheckpointSaver(checkpointSaver);

        PipelineState initState = new PipelineState();
        StateSnapshotVo snapshot = workflow.start("test-thread-004", initState);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(snapshot.state()).containsEntry(PipelineState.FAILED_STAGE.name(), "step2");
        assertThat(snapshot.state()).containsEntry("step1", "OK");
        assertThat(snapshot.state()).doesNotContainKey("step3");

        String error = (String) snapshot.state().get(TypedKey.ERROR.name());
        assertThat(error).contains("数据库写入超时模拟异常");
    }

    @Test
    @DisplayName("验证阶段标记 continueOnError=true 时发生异常容错并继续执行后续阶段")
    void testStageFailureContinueOnError() {
        ContinueOnErrorPipelineWorkflow workflow = new ContinueOnErrorPipelineWorkflow();
        workflow.setCheckpointSaver(checkpointSaver);

        PipelineState initState = new PipelineState();
        StateSnapshotVo snapshot = workflow.start("test-thread-005", initState);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(snapshot.state()).containsEntry("step1", "OK");
        assertThat(snapshot.state()).containsEntry("step3", "OK");

        @SuppressWarnings("unchecked")
        Map<String, Object> results = (Map<String, Object>) snapshot.state().get(PipelineState.STAGE_RESULTS.name());
        assertThat(results.get("step2")).toString().contains("FAILED: 非关键日志上报异常");
    }

    @Test
    @DisplayName("验证阶段元数据非法注册校验 (空列表、重复名称、保留字)")
    void testValidationFailures() {
        // 空阶段列表
        assertThatThrownBy(() -> {
            SequentialPipelineWorkflow<PipelineState> emptyWorkflow = new SequentialPipelineWorkflow<>("empty", PipelineState::new) {
                @Override
                protected List<PipelineStage<PipelineState>> registerStages() {
                    return List.of();
                }
            };
            emptyWorkflow.getStages();
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("必须至少注册一个流水线阶段");

        // 重复阶段标识
        assertThatThrownBy(() -> {
            SequentialPipelineWorkflow<PipelineState> dupWorkflow = new SequentialPipelineWorkflow<>("dup", PipelineState::new) {
                @Override
                protected List<PipelineStage<PipelineState>> registerStages() {
                    return List.of(
                            PipelineStage.of("stepA", state -> "a"),
                            PipelineStage.of("stepA", state -> "b")
                    );
                }
            };
            dupWorkflow.afterPropertiesSet();
        }).isInstanceOf(IllegalStateException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);

        // 系统保留字标识
        assertThatThrownBy(() -> {
            SequentialPipelineWorkflow<PipelineState> reservedWorkflow = new SequentialPipelineWorkflow<>("reserved", PipelineState::new) {
                @Override
                protected List<PipelineStage<PipelineState>> registerStages() {
                    return List.of(PipelineStage.of("finish", state -> "a"));
                }
            };
            reservedWorkflow.afterPropertiesSet();
        }).isInstanceOf(IllegalStateException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
