package io.github.atengk.boot.ai.graph.debate;

import io.github.atengk.boot.ai.graph.BaseGraphState;
import io.github.atengk.boot.ai.graph.GraphWorkflow;
import io.github.atengk.boot.ai.graph.TypedKey;
import io.github.atengk.boot.ai.graph.WorkflowStatus;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentStateFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
 * 基于 LangGraph4j 的对抗式多智能体评审与共识裁决认知状态图通用抽象基类
 * <p>
 * 提供多方辩手对抗交互、轮次递进、裁决员共识评估（Consensus Checking）与硬看门狗（Watchdog）收敛拓扑。
 * 支持在正方（Proposer）与反方/审查员（Critic）间进行多轮深度博弈，当达成共识时自动触发裁决终态；
 * 若达到最大辩论轮次上限，硬看门狗主动介入熔断降级，由裁决员实施强制仲裁收敛，彻底防御死循环对抗空转。
 * </p>
 *
 * @param <S> 强类型状态模型，继承自 {@link BaseGraphState}
 * @author Ateng
 * @since 2026-11-06
 */
public abstract class DebateConsensusWorkflow<S extends BaseGraphState> extends GraphWorkflow<S> {

    /**
     * 裁决员评判节点固定标识
     */
    public static final String NODE_ARBITER = "arbiter";

    /**
     * 正常达成共识完成终态节点固定标识
     */
    public static final String NODE_FINISH = "finish";

    /**
     * 看门狗熔断强制仲裁终态节点固定标识
     */
    public static final String NODE_FALLBACK_FINISH = "fallback_finish";

    /**
     * 默认最大对抗辩论轮次 (5 轮)
     */
    public static final int DEFAULT_MAX_TURNS = 5;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private List<DebateParticipant<S>> cachedDebaters;

    /**
     * 构建对抗式多智能体辩论工作流
     *
     * @param workflowName 工作流全局唯一名称
     * @param stateFactory 状态模型工厂
     */
    protected DebateConsensusWorkflow(String workflowName, AgentStateFactory<S> stateFactory) {
        super(workflowName, stateFactory);
    }

    /**
     * 注册当前工作流纳管的所有辩手参与方 (按发言先后顺序排列)
     *
     * @return 有序辩手参与方列表 (必须至少注册 1 名辩手，通常建议 2 名以上以构成对抗)
     */
    protected abstract List<DebateParticipant<S>> registerDebaters();

    /**
     * 评估当前轮次是否已达成共识
     *
     * @param state 当前状态模型快照
     * @return true 表示各方观点已收敛达成共识，false 表示存在分歧需继续对抗
     */
    protected abstract boolean checkConsensus(S state);

    /**
     * 达成共识时的裁决员结案终审钩子方法
     *
     * @param state 当前状态模型快照
     * @return 需要合并入状态机的最终裁决补丁字典
     */
    protected abstract Map<String, Object> adjudicate(S state);

    /**
     * 允许流转的最大辩论轮次硬看门狗限制 (默认 5 轮)
     *
     * @return 最大辩论轮次阈值
     */
    protected int maxDebateTurns() {
        return DEFAULT_MAX_TURNS;
    }

    /**
     * 获取不可变的已缓存辩手列表
     *
     * @return 辩手列表
     */
    public List<DebateParticipant<S>> getDebaters() {
        if (cachedDebaters == null) {
            List<DebateParticipant<S>> registered = registerDebaters();
            if (registered == null || registered.isEmpty()) {
                throw new IllegalStateException("DebateConsensusWorkflow [" + getWorkflowName() + "] 必须至少注册一名辩论参与方");
            }
            cachedDebaters = Collections.unmodifiableList(new ArrayList<>(registered));
        }
        return cachedDebaters;
    }

    @Override
    protected int maxIterations() {
        int debaterCount = getDebaters().size();
        return Math.max(50, maxDebateTurns() * (debaterCount + 2) + 10);
    }

    /**
     * 辩手发言完成记录回调钩子
     *
     * @param debater 当前发言辩手
     * @param state   当前状态模型
     * @param result  辩手处理器执行产出
     * @param round   当前对抗轮次
     * @return 需要合并入状态机的增量补丁字典
     */
    protected Map<String, Object> onDebaterSpeech(DebateParticipant<S> debater, S state, Object result, int round) {
        Map<String, Object> patch = new LinkedHashMap<>();

        // 1. 记录辩手最新论点产出
        Map<String, Object> currentArguments = new LinkedHashMap<>(state.get(DebateState.ARGUMENTS).orElseGet(LinkedHashMap::new));
        currentArguments.put(debater.getName(), result);
        patch.put(DebateState.ARGUMENTS.name(), currentArguments);

        // 2. 结构化追加历史发言轨迹
        String speechContent = (result instanceof String str) ? str : (result != null ? result.toString() : "");
        DebateSpeech speech = new DebateSpeech(round, debater.getName(), debater.getStance(), speechContent,
                LocalDateTime.now().format(FORMATTER));
        List<DebateSpeech> history = new ArrayList<>(state.get(DebateState.DEBATE_HISTORY).orElseGet(ArrayList::new));
        history.add(speech);
        patch.put(DebateState.DEBATE_HISTORY.name(), history);

        // 3. 若辩手返回 Map 增量补丁，合并展开至状态机顶层
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
     * 达成共识时的状态整合钩子
     *
     * @param state 当前状态模型
     * @return 状态增量补丁
     */
    protected Map<String, Object> onConsensusReached(S state) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(DebateState.CONSENSUS_REACHED.name(), true);
        patch.put(DebateState.WATCHDOG_TRIPPED.name(), false);

        Map<String, Object> adjudicatePatch = adjudicate(state);
        if (adjudicatePatch != null) {
            patch.putAll(adjudicatePatch);
        }
        return patch;
    }

    /**
     * 触发最大轮次硬看门狗时的降级仲裁钩子
     *
     * @param state 当前状态模型
     * @return 状态增量补丁
     */
    protected Map<String, Object> onWatchdogFallback(S state) {
        log.warn("[DEBATE-WATCHDOG:{}] 达到最大辩论轮次硬看门狗限制 [roundCount={}, maxDebateTurns={}]，强制熔断仲裁",
                getWorkflowName(), state.get(DebateState.ROUND_COUNT, 1), maxDebateTurns());
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(DebateState.CONSENSUS_REACHED.name(), false);
        patch.put(DebateState.WATCHDOG_TRIPPED.name(), true);
        patch.put(DebateState.FINAL_VERDICT.name(), "FORCED_ARBITRATION");
        patch.put(TypedKey.STATUS.name(), WorkflowStatus.COMPLETED.name());

        String currentSummary = state.get(DebateState.CONSENSUS_SUMMARY).orElse(null);
        if (currentSummary == null || currentSummary.isBlank()) {
            patch.put(DebateState.CONSENSUS_SUMMARY.name(),
                    "达到最大辩论轮次上限 (" + maxDebateTurns() + " 轮) 仍未收敛分歧，裁决员介入强制仲裁并下发基准决议");
        }

        String currentOutput = state.get(DebateState.FINAL_OUTPUT).orElse(null);
        if (currentOutput == null || currentOutput.isBlank()) {
            patch.put(DebateState.FINAL_OUTPUT.name(),
                    String.format("辩论流程 [%s] 触发看门狗熔断 (总轮次: %d)，执行强制仲裁收敛",
                            getWorkflowName(), maxDebateTurns()));
        }
        return patch;
    }

    /**
     * 正常达成共识流转至终态节点回调
     *
     * @param state 当前状态模型
     * @return 终态补丁
     */
    protected Map<String, Object> onFinish(S state) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(TypedKey.STATUS.name(), WorkflowStatus.COMPLETED.name());
        patch.put(DebateState.CONSENSUS_REACHED.name(), true);

        String verdict = state.get(DebateState.FINAL_VERDICT).orElse(null);
        if (verdict == null || verdict.isBlank()) {
            patch.put(DebateState.FINAL_VERDICT.name(), "APPROVED");
        }

        String output = state.get(DebateState.FINAL_OUTPUT).orElse(null);
        if (output == null || output.isBlank()) {
            patch.put(DebateState.FINAL_OUTPUT.name(),
                    String.format("对抗式评审 [%s] 于第 %d 轮顺利达成共识并完成裁决",
                            getWorkflowName(), state.get(DebateState.ROUND_COUNT, 1)));
        }
        return patch;
    }

    /**
     * 看门狗熔断流转至降级终态节点回调
     *
     * @param state 当前状态模型
     * @return 降级终态补丁
     */
    protected Map<String, Object> onFallbackFinish(S state) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(TypedKey.STATUS.name(), WorkflowStatus.COMPLETED.name());
        patch.put(DebateState.WATCHDOG_TRIPPED.name(), true);
        return patch;
    }

    @Override
    protected void initGraph(StateGraph<S> graph) throws GraphStateException {
        List<DebateParticipant<S>> debaters = getDebaters();

        // 1. 校验辩手标识合法性
        Set<String> uniqueNames = new HashSet<>();
        for (DebateParticipant<S> debater : debaters) {
            String name = debater.getName();
            if (NODE_ARBITER.equalsIgnoreCase(name) || NODE_FINISH.equalsIgnoreCase(name)
                    || NODE_FALLBACK_FINISH.equalsIgnoreCase(name) || START.equalsIgnoreCase(name) || END.equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("辩手标识不能使用系统保留字: " + name);
            }
            if (!uniqueNames.add(name)) {
                throw new IllegalArgumentException("辩手标识重复注册: " + name);
            }
        }

        // 2. 注册各个辩手节点
        for (DebateParticipant<S> debater : debaters) {
            graph.addNode(debater.getName(), node_async(state -> {
                int round = state.get(DebateState.ROUND_COUNT, 1);
                log.info("[DEBATE:{}] 辩手 [{}] (立场: {}) 开始执行第 {} 轮发言",
                        getWorkflowName(), debater.getName(), debater.getStance(), round);

                Object speechResult;
                try {
                    speechResult = debater.getHandler().handle(state, round);
                } catch (Exception e) {
                    log.error("[DEBATE:{}] 辩手 [{}] 发言执行异常: {}", getWorkflowName(), debater.getName(), e.getMessage(), e);
                    throw e;
                }

                return onDebaterSpeech(debater, state, speechResult, round);
            }));
        }

        // 3. 注册裁决员 Arbiter 节点
        graph.addNode(NODE_ARBITER, node_async(state -> {
            int currentRound = state.get(DebateState.ROUND_COUNT, 1);
            log.info("[ARBITER:{}] 裁决员开始第 {} 轮共识审查 (maxDebateTurns={})",
                    getWorkflowName(), currentRound, maxDebateTurns());

            boolean consensus = checkConsensus(state);
            if (consensus) {
                log.info("[ARBITER:{}] 第 {} 轮达成共识，生成裁决结论并收敛至终态", getWorkflowName(), currentRound);
                return onConsensusReached(state);
            }

            // 尚未达成共识，检查是否超出硬看门狗上限
            if (currentRound >= maxDebateTurns()) {
                log.warn("[ARBITER:{}] 第 {} 轮仍未达成共识且已达轮次上限，触发看门狗强制仲裁", getWorkflowName(), currentRound);
                return onWatchdogFallback(state);
            }

            // 未超限，递增轮次并继续推进
            log.info("[ARBITER:{}] 第 {} 轮未达成共识，推进至第 {} 轮博弈",
                    getWorkflowName(), currentRound, currentRound + 1);
            Map<String, Object> nextRoundPatch = new LinkedHashMap<>();
            nextRoundPatch.put(DebateState.ROUND_COUNT.name(), currentRound + 1);
            nextRoundPatch.put(DebateState.CONSENSUS_REACHED.name(), false);
            return nextRoundPatch;
        }));

        // 4. 注册完成终态与看门狗降级终态节点
        graph.addNode(NODE_FINISH, node_async(state -> {
            log.info("[DEBATE-FINISH:{}] 辩论流程到达正常完成终态节点", getWorkflowName());
            Map<String, Object> finishPatch = onFinish(state);
            return finishPatch != null ? finishPatch : Collections.emptyMap();
        }));

        graph.addNode(NODE_FALLBACK_FINISH, node_async(state -> {
            log.warn("[DEBATE-WATCHDOG-FINISH:{}] 辩论流程到达看门狗降级终态节点", getWorkflowName());
            Map<String, Object> fallbackPatch = onFallbackFinish(state);
            return fallbackPatch != null ? fallbackPatch : Collections.emptyMap();
        }));

        // 5. 拓扑边编排
        // START -> 第一个辩手
        graph.addEdge(START, debaters.get(0).getName());

        // 辩手之间严格串行流动
        for (int i = 0; i < debaters.size() - 1; i++) {
            graph.addEdge(debaters.get(i).getName(), debaters.get(i + 1).getName());
        }

        // 最后一个辩手 -> 裁决员
        graph.addEdge(debaters.get(debaters.size() - 1).getName(), NODE_ARBITER);

        // 裁决员条件路由：达成共识 -> NODE_FINISH；看门狗熔断 -> NODE_FALLBACK_FINISH；继续 -> 第一个辩手
        String firstDebater = debaters.get(0).getName();
        Map<String, String> edgeMappings = new LinkedHashMap<>();
        edgeMappings.put(NODE_FINISH, NODE_FINISH);
        edgeMappings.put(NODE_FALLBACK_FINISH, NODE_FALLBACK_FINISH);
        edgeMappings.put(firstDebater, firstDebater);

        graph.addConditionalEdges(NODE_ARBITER, edge_async(state -> {
            if (Boolean.TRUE.equals(state.get(DebateState.WATCHDOG_TRIPPED, false))
                    || state.get(DebateState.ROUND_COUNT, 1) > maxDebateTurns()) {
                return NODE_FALLBACK_FINISH;
            }
            if (Boolean.TRUE.equals(state.get(DebateState.CONSENSUS_REACHED, false))) {
                return NODE_FINISH;
            }
            return firstDebater;
        }), edgeMappings);

        // 终态节点连向 END
        graph.addEdge(NODE_FINISH, END);
        graph.addEdge(NODE_FALLBACK_FINISH, END);
    }
}
