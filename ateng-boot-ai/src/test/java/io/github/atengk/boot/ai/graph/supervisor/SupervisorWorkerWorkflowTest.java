package io.github.atengk.boot.ai.graph.supervisor;

import io.github.atengk.boot.ai.checkpoint.MemoryCheckpointSaver;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.graph.StateSnapshotVo;
import io.github.atengk.boot.ai.graph.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 主管-专工认知状态图工作流与死循环硬看门狗单元测试
 *
 * @author Ateng
 * @since 2026-11-06
 */
class SupervisorWorkerWorkflowTest {

    private MemoryCheckpointSaver checkpointSaver;

    @BeforeEach
    void setUp() {
        checkpointSaver = new MemoryCheckpointSaver();
    }

    /**
     * 正常多智能体协同流水线：主管依次调度 researcher -> coder -> 完成
     */
    static class RndSupervisorWorkflow extends SupervisorWorkerWorkflow<SupervisorState> {

        public RndSupervisorWorkflow() {
            super("rndWorkflow", SupervisorState::new);
        }

        @Override
        protected SupervisorDecision decide(SupervisorState state) {
            Map<String, Object> results = state.getWorkerResults();
            if (!results.containsKey("researcher")) {
                return SupervisorDecision.next("researcher", "调研订单架构方案", "阶段1: 架构预研");
            }
            if (!results.containsKey("coder")) {
                return SupervisorDecision.next("coder", "根据调研结果编写业务代码", "阶段2: 编码实现");
            }
            return SupervisorDecision.finish("综合报告：需求研发全流程顺利完成！");
        }

        @Override
        protected Map<String, WorkerHandler<SupervisorState>> registerWorkers() {
            Map<String, WorkerHandler<SupervisorState>> workers = new LinkedHashMap<>();
            workers.put("researcher", (state, instructions) -> "方案：基于 Redis 延时队列设计");
            workers.put("coder", (state, instructions) -> Map.of(
                    "codeSnippet", "class OrderService {}",
                    "linesOfCode", 120
            ));
            return workers;
        }
    }

    /**
     * 故意制造死循环工作流：专工与主管无限往复
     */
    static class InfiniteLoopSupervisorWorkflow extends SupervisorWorkerWorkflow<SupervisorState> {

        public InfiniteLoopSupervisorWorkflow() {
            super("infiniteLoopWorkflow", SupervisorState::new);
        }

        @Override
        protected int maxTurns() {
            return 3; // 设置硬看门狗上限为 3 轮
        }

        @Override
        protected SupervisorDecision decide(SupervisorState state) {
            // 永远不结束，无休止派发给 pingPong 专工
            return SupervisorDecision.next("pingPong", "继续循环执行第 " + state.getTurnCount() + " 步");
        }

        @Override
        protected Map<String, WorkerHandler<SupervisorState>> registerWorkers() {
            return Map.of("pingPong", (state, instructions) -> "pong:" + state.getTurnCount());
        }
    }

    /**
     * 专工抛出异常的工作流
     */
    static class FaultyWorkerWorkflow extends SupervisorWorkerWorkflow<SupervisorState> {

        public FaultyWorkerWorkflow() {
            super("faultyWorkflow", SupervisorState::new);
        }

        @Override
        protected SupervisorDecision decide(SupervisorState state) {
            return SupervisorDecision.next("brokenWorker", "执行有缺陷的任务");
        }

        @Override
        protected Map<String, WorkerHandler<SupervisorState>> registerWorkers() {
            return Map.of("brokenWorker", (state, instructions) -> {
                throw new IllegalStateException("专工驱动发生不可恢复的故障");
            });
        }
    }

    /**
     * 主管派发未知专工的工作流
     */
    static class UnknownWorkerWorkflow extends SupervisorWorkerWorkflow<SupervisorState> {

        public UnknownWorkerWorkflow() {
            super("unknownWorkerWorkflow", SupervisorState::new);
        }

        @Override
        protected SupervisorDecision decide(SupervisorState state) {
            return SupervisorDecision.next("ghostWorker", "派发给不存在的专工");
        }

        @Override
        protected Map<String, WorkerHandler<SupervisorState>> registerWorkers() {
            return Map.of("existingWorker", (state, instructions) -> "ok");
        }
    }

    @Test
    @DisplayName("正常协同流转：主管成功规划并调度 researcher 与 coder，最终汇总输出")
    void testNormalMultiAgentCycle() throws Exception {
        RndSupervisorWorkflow workflow = new RndSupervisorWorkflow();
        workflow.setCheckpointSaver(checkpointSaver);
        workflow.afterPropertiesSet();

        SupervisorState initState = new SupervisorState();
        initState.setTaskObjective("实现订单延时关单业务功能");

        StateSnapshotVo snapshot = workflow.start("rnd-thread-001", initState);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(snapshot.isCompleted()).isTrue();

        Map<String, Object> stateMap = snapshot.state();
        assertThat(stateMap.get("turnCount")).isEqualTo(3);
        assertThat(stateMap.get("isFinished")).isEqualTo(true);
        assertThat(stateMap.get("finalOutput")).isEqualTo("综合报告：需求研发全流程顺利完成！");

        @SuppressWarnings("unchecked")
        Map<String, Object> workerResults = (Map<String, Object>) stateMap.get("workerResults");
        assertThat(workerResults).isNotNull();
        assertThat(workerResults.get("researcher")).isEqualTo("方案：基于 Redis 延时队列设计");
        assertThat(workerResults).containsKey("coder");

        // 验证专工返回 Map 补丁直接展开合并至状态机
        assertThat(stateMap.get("codeSnippet")).isEqualTo("class OrderService {}");
        assertThat(stateMap.get("linesOfCode")).isEqualTo(120);
    }

    @Test
    @DisplayName("死循环硬看门狗熔断：达到 maxTurns 上限时强制熔断终止流转至终态")
    void testWatchdogLoopBreak() throws Exception {
        InfiniteLoopSupervisorWorkflow workflow = new InfiniteLoopSupervisorWorkflow();
        workflow.setCheckpointSaver(checkpointSaver);
        workflow.afterPropertiesSet();

        SupervisorState initState = new SupervisorState();
        initState.setTaskObjective("测试死循环看门狗防御");

        StateSnapshotVo snapshot = workflow.start("watchdog-thread-001", initState);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(snapshot.isCompleted()).isTrue();

        Map<String, Object> stateMap = snapshot.state();
        // 轮次应该达到 4 (1, 2, 3 正常流转，第 4 轮超限看门狗熔断)
        assertThat(stateMap.get("turnCount")).isEqualTo(4);
        assertThat(stateMap.get("isFinished")).isEqualTo(true);
        assertThat(String.valueOf(stateMap.get("finalOutput"))).contains("触发安全熔断终止");
    }

    @Test
    @DisplayName("专工异常隔离转译：专工抛出异常时受控转译为 AiException 并保留因果链")
    void testWorkerExceptionDecoupling() throws Exception {
        FaultyWorkerWorkflow workflow = new FaultyWorkerWorkflow();
        workflow.setCheckpointSaver(checkpointSaver);
        workflow.afterPropertiesSet();

        SupervisorState initState = new SupervisorState();
        initState.setTaskObjective("测试专工异常");

        assertThatThrownBy(() -> workflow.start("faulty-thread-001", initState))
                .isInstanceOf(AiException.class)
                .hasMessageContaining("执行失败");
    }

    @Test
    @DisplayName("未知专工降级防御：主管决断返回不存在的专工标识时，安全降级至终态")
    void testUnknownWorkerFallback() throws Exception {
        UnknownWorkerWorkflow workflow = new UnknownWorkerWorkflow();
        workflow.setCheckpointSaver(checkpointSaver);
        workflow.afterPropertiesSet();

        SupervisorState initState = new SupervisorState();
        initState.setTaskObjective("测试未知专工");

        StateSnapshotVo snapshot = workflow.start("unknown-thread-001", initState);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(WorkflowStatus.COMPLETED);
    }
}
