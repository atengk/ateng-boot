package io.github.atengk.boot.ai.graph.supervisor;

import io.github.atengk.boot.ai.exception.AiExceptionTranslator;
import io.github.atengk.boot.ai.graph.GraphWorkflow;
import io.github.atengk.boot.ai.graph.TypedKey;
import io.github.atengk.boot.ai.graph.WorkflowStatus;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentStateFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 基于 LangGraph4j 的主管-专工（Supervisor-Worker）分层认知状态图通用抽象基类
 * <p>
 * 提供中心主管规划调度、专工 Agent 协同流转与状态聚合的标准拓扑。
 * 主管节点负责分析全局目标并下发强类型决断 {@link SupervisorDecision}，通过条件路由派发至各专工节点，
 * 专工节点执行完成后自动回流至主管节点。
 * <p>
 * 内置 {@link #maxTurns()} 硬看门狗机制，当循环轮次达到阈值时自动触发安全熔断，
 * 跳转至终态节点，彻底消除多 Agent 失控发散与死循环空转问题。
 * </p>
 *
 * @param <S> 强类型状态模型，继承自 {@link SupervisorState}
 * @author Ateng
 * @since 2026-11-06
 */
public abstract class SupervisorWorkerWorkflow<S extends SupervisorState> extends GraphWorkflow<S> {

    /**
     * 主管节点固定标识
     */
    public static final String NODE_SUPERVISOR = "supervisor";

    /**
     * 终态节点固定标识
     */
    public static final String NODE_FINISH = "finish";

    /**
     * 默认最大轮次硬看门狗限制（10 轮）
     */
    public static final int DEFAULT_MAX_TURNS = 10;

    /**
     * 构建主管-专工认知工作流
     *
     * @param workflowName 工作流唯一名称
     * @param stateFactory 状态模型工厂
     */
    protected SupervisorWorkerWorkflow(String workflowName, AgentStateFactory<S> stateFactory) {
        super(workflowName, stateFactory);
    }

    /**
     * 主管决策钩子方法
     * <p>
     * 依据当前状态快照（包括全局目标、各专工历史结果等），分析并产出下一步调度决策。
     * 支持在子类中调用 {@code AiTemplate.chatForObject} 依托大模型生成，亦支持规则/混合式推断。
     * </p>
     *
     * @param state 当前状态模型
     * @return 调度决策对象
     */
    protected abstract SupervisorDecision decide(S state);

    /**
     * 注册当前工作流纳管的所有专工 Worker 清单
     *
     * @return 专工名称与处理器映射（不可为空）
     */
    protected abstract Map<String, WorkerHandler<S>> registerWorkers();

    /**
     * 最大循环轮次硬看门狗阈值（默认 10 轮）
     *
     * @return 允许流转的最大主管决断轮次
     */
    protected int maxTurns() {
        return DEFAULT_MAX_TURNS;
    }

    /**
     * 终态结算钩子方法
     * <p>
     * 工作流流转至 {@link #NODE_FINISH} 节点时调用，可用于执行最终输出汇总、审计归档或指标统计。
     * 默认将工作流状态置为 {@link WorkflowStatus#COMPLETED}。
     * </p>
     *
     * @param state 当前状态模型
     * @return 需要合并入状态机的增量补丁字典
     */
    protected Map<String, Object> onFinish(S state) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(TypedKey.STATUS.name(), WorkflowStatus.COMPLETED.name());
        if (state.getFinalOutput() == null || state.getFinalOutput().isBlank()) {
            patch.put(SupervisorState.FINAL_OUTPUT.name(), "多智能体协同任务执行完毕");
        }
        return patch;
    }

    @Override
    protected int maxIterations() {
        // LangGraph4j 内部步数防卫：主管+专工每轮至少2步，外加余量，确保由业务看门狗 maxTurns() 优先决断
        return Math.max(50, maxTurns() * 2 + 10);
    }

    @Override
    protected void initGraph(StateGraph<S> graph) throws GraphStateException {
        Map<String, WorkerHandler<S>> workers = registerWorkers();
        if (workers == null || workers.isEmpty()) {
            throw new IllegalStateException("SupervisorWorkerWorkflow [" + getWorkflowName() + "] 必须至少注册一个专工 Worker");
        }

        // 1. 主管节点 (Supervisor Node)
        graph.addNode(NODE_SUPERVISOR, node_async(state -> {
            int currentTurn = state.getTurnCount();
            int newTurn = currentTurn + 1;
            log.info("[SUPERVISOR:{}] 开始第 {} 轮主管规划决断 (maxTurns={})", getWorkflowName(), newTurn, maxTurns());

            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put(SupervisorState.TURN_COUNT.name(), newTurn);

            // 硬看门狗：如果轮次已超限，强制触发熔断
            if (newTurn > maxTurns()) {
                log.warn("[SUPERVISOR-WATCHDOG:{}] 工作流达到最大轮次硬看门狗限制 [turnCount={}, maxTurns={}]，强制熔断终止流转至终态",
                        getWorkflowName(), newTurn, maxTurns());
                patch.put(SupervisorState.IS_FINISHED.name(), true);
                patch.put(SupervisorState.NEXT_WORKER.name(), NODE_FINISH);
                String fallbackOutput = state.get(SupervisorState.FINAL_OUTPUT)
                        .filter(s -> !s.isBlank())
                        .orElseGet(() -> "已达到最大主管调度轮次限制 (" + maxTurns() + " 轮)，触发安全熔断终止");
                patch.put(SupervisorState.FINAL_OUTPUT.name(), fallbackOutput);
                return patch;
            }

            SupervisorDecision decision;
            try {
                decision = decide(state);
            } catch (Exception e) {
                log.error("[SUPERVISOR:{}] 主管决断异常: {}", getWorkflowName(), e.getMessage(), e);
                throw AiExceptionTranslator.translate("主管 [" + getWorkflowName() + "] 规划决断失败", e);
            }

            if (decision == null) {
                log.warn("[SUPERVISOR:{}] 主管决断返回 null，安全回退至终态", getWorkflowName());
                patch.put(SupervisorState.IS_FINISHED.name(), true);
                patch.put(SupervisorState.NEXT_WORKER.name(), NODE_FINISH);
                return patch;
            }

            if (decision.getCurrentPlan() != null) {
                patch.put(SupervisorState.CURRENT_PLAN.name(), decision.getCurrentPlan());
            }
            if (decision.getNextWorker() != null) {
                patch.put(SupervisorState.NEXT_WORKER.name(), decision.getNextWorker());
            }
            if (decision.getInstructions() != null) {
                patch.put(SupervisorState.INSTRUCTIONS.name(), decision.getInstructions());
            }
            if (decision.getFinalOutput() != null) {
                patch.put(SupervisorState.FINAL_OUTPUT.name(), decision.getFinalOutput());
            }
            patch.put(SupervisorState.IS_FINISHED.name(), decision.isFinished());

            return patch;
        }));

        // 2. 专工 Worker 节点池
        for (Map.Entry<String, WorkerHandler<S>> entry : workers.entrySet()) {
            String workerName = entry.getKey();
            WorkerHandler<S> handler = entry.getValue();

            graph.addNode(workerName, node_async(state -> {
                String instructions = state.getInstructions();
                log.info("[WORKER:{}:{}] 专工开始执行任务，指令: {}", getWorkflowName(), workerName, instructions);

                Object result;
                try {
                    result = handler.handle(state, instructions);
                } catch (Exception e) {
                    log.error("[WORKER:{}:{}] 专工执行发生异常: {}", getWorkflowName(), workerName, e.getMessage(), e);
                    throw AiExceptionTranslator.translate("专工 [" + workerName + "] 执行失败", e);
                }

                Map<String, Object> patch = new LinkedHashMap<>();
                Map<String, Object> currentResults = new LinkedHashMap<>(state.getWorkerResults());
                currentResults.put(workerName, result);
                patch.put(SupervisorState.WORKER_RESULTS.name(), currentResults);

                // 若专工返回 Map 增量补丁，合并展开至状态机
                if (result instanceof Map<?, ?> resultMap) {
                    for (Map.Entry<?, ?> mapEntry : resultMap.entrySet()) {
                        if (mapEntry.getKey() instanceof String k) {
                            patch.put(k, mapEntry.getValue());
                        }
                    }
                }

                return patch;
            }));

            // 专工执行完毕回流主管节点
            graph.addEdge(workerName, NODE_SUPERVISOR);
        }

        // 3. 终态节点 (Finish Node)
        graph.addNode(NODE_FINISH, node_async(state -> {
            log.info("[SUPERVISOR-FINISH:{}] 工作流到达终态节点，总轮次: {}", getWorkflowName(), state.getTurnCount());
            Map<String, Object> finishPatch = onFinish(state);
            return finishPatch != null ? finishPatch : Collections.emptyMap();
        }));

        // 4. 起始连线
        graph.addEdge(START, NODE_SUPERVISOR);

        // 5. 条件路由分发
        Map<String, String> edgeMappings = new LinkedHashMap<>();
        edgeMappings.put(NODE_FINISH, NODE_FINISH);
        edgeMappings.put(END, END);
        for (String workerName : workers.keySet()) {
            edgeMappings.put(workerName, workerName);
        }

        graph.addConditionalEdges(NODE_SUPERVISOR, edge_async(state -> {
            // 条件 1: 标记完成或轮次已达看门狗上限
            if (state.isFinished() || state.getTurnCount() > maxTurns()) {
                log.info("[SUPERVISOR:{}] 任务标记完成或看门狗触发，跳转终态", getWorkflowName());
                return NODE_FINISH;
            }

            String nextWorker = state.getNextWorker();
            // 条件 2: 指向完成或空
            if (nextWorker == null || nextWorker.isBlank() || "FINISH".equalsIgnoreCase(nextWorker) || END.equalsIgnoreCase(nextWorker)) {
                log.info("[SUPERVISOR:{}] 未指派专工或指派 FINISH，跳转终态", getWorkflowName());
                return NODE_FINISH;
            }

            // 条件 3: 命中专工
            if (workers.containsKey(nextWorker)) {
                return nextWorker;
            }

            // 条件 4: 未知专工，告警并安全降级到终态
            log.warn("[SUPERVISOR:{}] 未知专工 [{}]，可用专工: {}，安全跳转终态",
                    getWorkflowName(), nextWorker, workers.keySet());
            return NODE_FINISH;
        }), edgeMappings);

        // 6. 终态节点连向 END
        graph.addEdge(NODE_FINISH, END);
    }
}
