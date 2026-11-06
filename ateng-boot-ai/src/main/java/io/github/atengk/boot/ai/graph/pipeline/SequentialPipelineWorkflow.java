package io.github.atengk.boot.ai.graph.pipeline;

import io.github.atengk.boot.ai.graph.BaseGraphState;
import io.github.atengk.boot.ai.graph.GraphWorkflow;
import io.github.atengk.boot.ai.graph.TypedKey;
import io.github.atengk.boot.ai.graph.WorkflowStatus;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentStateFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 基于 LangGraph4j 的确定性顺序流水线认知状态图通用抽象基类
 * <p>
 * 提供确定性多阶段串行推进、条件跳过（Skip Condition）、断点续跑与异常熔断的标准拓扑。
 * 开发者仅需实现 {@link #registerStages()} 注册有序的阶段列表，底层自动编译严格顺序边（Sequential Edges），
 * 支持阶段执行成果聚合、全真检查点持久化以及人机协同（HITL）审批中断挂起。
 * </p>
 *
 * @param <S> 强类型状态模型，继承自 {@link BaseGraphState}
 * @author Ateng
 * @since 2026-11-06
 */
public abstract class SequentialPipelineWorkflow<S extends BaseGraphState> extends GraphWorkflow<S> {

    /**
     * 正常完成终态节点固定标识
     */
    public static final String NODE_FINISH = "finish";

    /**
     * 异常熔断失败终态节点固定标识
     */
    public static final String NODE_FAILED = "failed";

    private List<PipelineStage<S>> cachedStages;

    /**
     * 构建确定性顺序流水线工作流
     *
     * @param workflowName 工作流全局唯一名称
     * @param stateFactory 状态模型工厂
     */
    protected SequentialPipelineWorkflow(String workflowName, AgentStateFactory<S> stateFactory) {
        super(workflowName, stateFactory);
    }

    /**
     * 注册当前流水线纳管的所有有序阶段列表
     *
     * @return 顺序流水线阶段集合（不可为空且阶段标识不可重复）
     */
    protected abstract List<PipelineStage<S>> registerStages();

    /**
     * 获取不可变的已缓存阶段列表
     *
     * @return 阶段列表
     */
    public List<PipelineStage<S>> getStages() {
        if (cachedStages == null) {
            List<PipelineStage<S>> registered = registerStages();
            if (registered == null || registered.isEmpty()) {
                throw new IllegalStateException("SequentialPipelineWorkflow [" + getWorkflowName() + "] 必须至少注册一个流水线阶段");
            }
            cachedStages = Collections.unmodifiableList(new ArrayList<>(registered));
        }
        return cachedStages;
    }

    /**
     * 自定义前置静态中断节点（子类可扩展）
     *
     * @return 节点名称列表
     */
    protected List<String> customInterruptBeforeNodes() {
        return Collections.emptyList();
    }

    @Override
    protected List<String> interruptBeforeNodes() {
        List<String> nodes = new ArrayList<>(customInterruptBeforeNodes());
        for (PipelineStage<S> stage : getStages()) {
            if (stage.isInterruptBefore() && !nodes.contains(stage.getName())) {
                nodes.add(stage.getName());
            }
        }
        return nodes;
    }

    @Override
    protected int maxIterations() {
        int stagesCount = getStages().size();
        return Math.max(50, stagesCount * 3 + 10);
    }

    /**
     * 阶段成功执行完成回调钩子
     *
     * @param stage  当前执行阶段
     * @param state  当前状态模型
     * @param result 阶段处理器返回结果
     * @return 需要合并入状态机的增量补丁字典
     */
    protected Map<String, Object> onStageSuccess(PipelineStage<S> stage, S state, Object result) {
        Map<String, Object> patch = new LinkedHashMap<>();

        // 1. 更新当前阶段
        patch.put(PipelineState.CURRENT_STAGE.name(), stage.getName());

        // 2. 累加已完成阶段
        List<String> completed = new ArrayList<>(state.get(PipelineState.COMPLETED_STAGES).orElseGet(ArrayList::new));
        if (!completed.contains(stage.getName())) {
            completed.add(stage.getName());
        }
        patch.put(PipelineState.COMPLETED_STAGES.name(), completed);

        // 3. 计算沿途跳过的阶段
        List<String> skipped = new ArrayList<>(state.get(PipelineState.SKIPPED_STAGES).orElseGet(ArrayList::new));
        List<PipelineStage<S>> stages = getStages();
        int currentIndex = -1;
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).getName().equals(stage.getName())) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex > 0) {
            for (int i = 0; i < currentIndex; i++) {
                String prevName = stages.get(i).getName();
                if (!completed.contains(prevName) && !skipped.contains(prevName)) {
                    skipped.add(prevName);
                }
            }
        }
        patch.put(PipelineState.SKIPPED_STAGES.name(), skipped);

        // 4. 记录阶段产出结果字典
        Map<String, Object> stageResults = new LinkedHashMap<>(state.get(PipelineState.STAGE_RESULTS).orElseGet(LinkedHashMap::new));
        if (result != null) {
            stageResults.put(stage.getName(), result);
        }
        patch.put(PipelineState.STAGE_RESULTS.name(), stageResults);

        // 4. 若阶段产出为 Map 增量补丁，合并展开至状态机顶层
        if (result instanceof Map<?, ?> resultMap) {
            for (Map.Entry<?, ?> entry : resultMap.entrySet()) {
                if (entry.getKey() instanceof String k) {
                    patch.put(k, entry.getValue());
                }
            }
        }

        return patch;
    }

    /**
     * 阶段条件跳过回调钩子
     *
     * @param stage 当前跳过的阶段
     * @param state 当前状态模型
     * @return 需要合并入状态机的增量补丁字典
     */
    protected Map<String, Object> onStageSkipped(PipelineStage<S> stage, S state) {
        Map<String, Object> patch = new LinkedHashMap<>();

        patch.put(PipelineState.CURRENT_STAGE.name(), stage.getName());

        List<String> skipped = new ArrayList<>(state.get(PipelineState.SKIPPED_STAGES).orElseGet(ArrayList::new));
        if (!skipped.contains(stage.getName())) {
            skipped.add(stage.getName());
        }
        patch.put(PipelineState.SKIPPED_STAGES.name(), skipped);

        Map<String, Object> stageResults = new LinkedHashMap<>(state.get(PipelineState.STAGE_RESULTS).orElseGet(LinkedHashMap::new));
        stageResults.put(stage.getName(), "SKIPPED");
        patch.put(PipelineState.STAGE_RESULTS.name(), stageResults);

        return patch;
    }

    /**
     * 阶段执行发生异常回调钩子
     *
     * @param stage 当前发生异常的阶段
     * @param state 当前状态模型
     * @param error 异常对象
     * @return 需要合并入状态机的增量补丁字典
     */
    protected Map<String, Object> onStageFailure(PipelineStage<S> stage, S state, Exception error) {
        log.error("[PIPELINE:{}] 阶段 [{}] 执行失败: {}", getWorkflowName(), stage.getName(), error.getMessage(), error);
        Map<String, Object> patch = new LinkedHashMap<>();

        patch.put(PipelineState.CURRENT_STAGE.name(), stage.getName());
        patch.put(PipelineState.FAILED_STAGE.name(), stage.getName());
        patch.put(TypedKey.ERROR.name(), "阶段 [" + stage.getName() + "] 执行异常: " + error.getMessage());

        if (!stage.isContinueOnError()) {
            patch.put(TypedKey.STATUS.name(), WorkflowStatus.FAILED.name());
        }

        Map<String, Object> stageResults = new LinkedHashMap<>(state.get(PipelineState.STAGE_RESULTS).orElseGet(LinkedHashMap::new));
        stageResults.put(stage.getName(), "FAILED: " + error.getMessage());
        patch.put(PipelineState.STAGE_RESULTS.name(), stageResults);

        return patch;
    }

    /**
     * 全流程顺利完成终态回调钩子
     *
     * @param state 当前状态模型
     * @return 需要合并入状态机的增量补丁字典
     */
    protected Map<String, Object> onFinish(S state) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(TypedKey.STATUS.name(), WorkflowStatus.COMPLETED.name());
        patch.put(PipelineState.IS_COMPLETED.name(), true);

        List<String> completed = state.get(PipelineState.COMPLETED_STAGES).orElseGet(Collections::emptyList);
        List<String> skipped = new ArrayList<>(state.get(PipelineState.SKIPPED_STAGES).orElseGet(ArrayList::new));
        for (PipelineStage<S> s : getStages()) {
            if (!completed.contains(s.getName()) && !skipped.contains(s.getName())) {
                skipped.add(s.getName());
            }
        }
        patch.put(PipelineState.SKIPPED_STAGES.name(), skipped);

        String currentOutput = state.get(PipelineState.FINAL_OUTPUT).orElse(null);
        if (currentOutput == null || currentOutput.isBlank()) {
            patch.put(PipelineState.FINAL_OUTPUT.name(),
                    String.format("流水线 [%s] 执行完成 (完成阶段: %d, 跳过阶段: %d)",
                            getWorkflowName(), completed.size(), skipped.size()));
        }
        return patch;
    }

    /**
     * 异常熔断失败终态回调钩子
     *
     * @param state 当前状态模型
     * @return 需要合并入状态机的增量补丁字典
     */
    protected Map<String, Object> onFallback(S state) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(TypedKey.STATUS.name(), WorkflowStatus.FAILED.name());
        patch.put(PipelineState.IS_COMPLETED.name(), false);

        String error = state.get(TypedKey.ERROR).orElse("流水线执行中途发生不可恢复错误");
        String failedStage = state.get(PipelineState.FAILED_STAGE).orElse("未知阶段");

        String currentOutput = state.get(PipelineState.FINAL_OUTPUT).orElse(null);
        if (currentOutput == null || currentOutput.isBlank()) {
            patch.put(PipelineState.FINAL_OUTPUT.name(),
                    String.format("流水线 [%s] 于阶段 [%s] 中断失败: %s",
                            getWorkflowName(), failedStage, error));
        }
        return patch;
    }

    @Override
    protected void initGraph(StateGraph<S> graph) throws GraphStateException {
        List<PipelineStage<S>> stages = getStages();
        if (stages.isEmpty()) {
            throw new IllegalStateException("SequentialPipelineWorkflow [" + getWorkflowName() + "] 必须至少注册一个流水线阶段");
        }

        // 1. 校验阶段标识合法性与唯一性
        Set<String> uniqueNames = new HashSet<>();
        for (PipelineStage<S> stage : stages) {
            String name = stage.getName();
            if (NODE_FINISH.equalsIgnoreCase(name) || NODE_FAILED.equalsIgnoreCase(name)
                    || START.equalsIgnoreCase(name) || END.equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("流水线阶段标识不能使用系统保留字: " + name);
            }
            if (!uniqueNames.add(name)) {
                throw new IllegalArgumentException("流水线阶段标识重复注册: " + name);
            }
        }

        // 2. 注册各个阶段节点
        for (PipelineStage<S> stage : stages) {
            graph.addNode(stage.getName(), node_async(state -> {
                log.info("[PIPELINE:{}] 进入阶段 [{}]", getWorkflowName(), stage.getName());

                // 条件跳过判定
                if (stage.shouldSkip(state)) {
                    log.info("[PIPELINE:{}] 阶段 [{}] 满足跳过条件，执行跳过", getWorkflowName(), stage.getName());
                    return onStageSkipped(stage, state);
                }

                // 执行阶段具体业务
                try {
                    Object result = stage.getHandler().handle(state);
                    log.info("[PIPELINE:{}] 阶段 [{}] 业务执行成功", getWorkflowName(), stage.getName());
                    return onStageSuccess(stage, state, result);
                } catch (Exception e) {
                    Map<String, Object> failPatch = onStageFailure(stage, state, e);
                    if (!stage.isContinueOnError()) {
                        failPatch.put(TypedKey.STATUS.name(), WorkflowStatus.FAILED.name());
                    }
                    return failPatch;
                }
            }));
        }

        // 3. 注册终态完成节点与失败降级节点
        graph.addNode(NODE_FINISH, node_async(state -> {
            log.info("[PIPELINE:{}] 流水线到达完成终态节点", getWorkflowName());
            Map<String, Object> finishPatch = onFinish(state);
            return finishPatch != null ? finishPatch : Collections.emptyMap();
        }));

        graph.addNode(NODE_FAILED, node_async(state -> {
            log.warn("[PIPELINE:{}] 流水线到达失败终态节点", getWorkflowName());
            Map<String, Object> fallbackPatch = onFallback(state);
            return fallbackPatch != null ? fallbackPatch : Collections.emptyMap();
        }));

        // 4. 起始连线：START 连向第一个阶段
        graph.addEdge(START, stages.get(0).getName());

        // 5. 阶段间严格顺序条件路由 (支持异常快速熔断与下游条件跳过前瞻)
        for (int i = 0; i < stages.size(); i++) {
            PipelineStage<S> currentStage = stages.get(i);
            final int currentIndex = i;

            Map<String, String> edgeMappings = new LinkedHashMap<>();
            edgeMappings.put(NODE_FAILED, NODE_FAILED);
            edgeMappings.put(NODE_FINISH, NODE_FINISH);
            for (int j = i + 1; j < stages.size(); j++) {
                String stageName = stages.get(j).getName();
                edgeMappings.put(stageName, stageName);
            }

            graph.addConditionalEdges(currentStage.getName(), edge_async(state -> {
                String status = state.get(TypedKey.STATUS).orElse(null);
                if (WorkflowStatus.FAILED.name().equalsIgnoreCase(status)) {
                    log.warn("[PIPELINE:{}] 阶段 [{}] 标记为失败，跳转熔断终态节点 [{}]",
                            getWorkflowName(), currentStage.getName(), NODE_FAILED);
                    return NODE_FAILED;
                }

                // 前瞻寻找后续首个不满足跳过条件的阶段
                for (int j = currentIndex + 1; j < stages.size(); j++) {
                    PipelineStage<S> candidateStage = stages.get(j);
                    if (!candidateStage.shouldSkip(state)) {
                        return candidateStage.getName();
                    }
                }
                return NODE_FINISH;
            }), edgeMappings);
        }

        // 6. 终态节点连向 END
        graph.addEdge(NODE_FINISH, END);
        graph.addEdge(NODE_FAILED, END);
    }
}
