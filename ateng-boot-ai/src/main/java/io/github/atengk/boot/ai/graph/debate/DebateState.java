package io.github.atengk.boot.ai.graph.debate;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.atengk.boot.ai.graph.BaseGraphState;
import io.github.atengk.boot.ai.graph.TypedKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对抗式多智能体辩论与共识裁决强类型状态契约模型
 * <p>
 * 继承自 {@link BaseGraphState}，集中收敛辩论议题、对抗轮次、发言历史轨迹、
 * 各辩手最新论点与结构化补丁、共识判定结果、最终裁决结论以及看门狗熔断状态。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class DebateState extends BaseGraphState {

    /**
     * 辩论议题 / 待评审方案
     */
    public static final TypedKey<String> TOPIC = TypedKey.of("topic", String.class);

    /**
     * 当前辩论轮次计数器 (从 1 开始)
     */
    public static final TypedKey<Integer> ROUND_COUNT = TypedKey.of("roundCount", Integer.class);

    /**
     * 历史发言轨迹列表
     */
    public static final TypedKey<List<DebateSpeech>> DEBATE_HISTORY =
            TypedKey.of("debateHistory", new TypeReference<List<DebateSpeech>>() {});

    /**
     * 各辩手最新论点与增量产出字典 (键为辩手标识)
     */
    public static final TypedKey<Map<String, Object>> ARGUMENTS =
            TypedKey.of("arguments", new TypeReference<Map<String, Object>>() {});

    /**
     * 是否已达成裁决共识
     */
    public static final TypedKey<Boolean> CONSENSUS_REACHED = TypedKey.of("consensusReached", Boolean.class);

    /**
     * 共识达成理由或裁决意见摘要
     */
    public static final TypedKey<String> CONSENSUS_SUMMARY = TypedKey.of("consensusSummary", String.class);

    /**
     * 裁决结论 (如 APPROVED / REJECTED / FORCED_ARBITRATION)
     */
    public static final TypedKey<String> FINAL_VERDICT = TypedKey.of("finalVerdict", String.class);

    /**
     * 最终综合输出报告
     */
    public static final TypedKey<String> FINAL_OUTPUT = TypedKey.of("finalOutput", String.class);

    /**
     * 是否触发最大轮次硬看门狗熔断
     */
    public static final TypedKey<Boolean> WATCHDOG_TRIPPED = TypedKey.of("watchdogTripped", Boolean.class);

    /**
     * 无参缺省构造器
     */
    public DebateState() {
        this(new LinkedHashMap<>());
    }

    /**
     * 基于初始字典构造状态模型
     *
     * @param initData 初始状态字典
     */
    public DebateState(Map<String, Object> initData) {
        super(initData != null ? new LinkedHashMap<>(initData) : new LinkedHashMap<>());
    }

    public String getTopic() {
        return get(TOPIC, "");
    }

    public void setTopic(String topic) {
        set(TOPIC, topic);
    }

    public int getRoundCount() {
        return get(ROUND_COUNT, 1);
    }

    public void setRoundCount(int roundCount) {
        set(ROUND_COUNT, roundCount);
    }

    public List<DebateSpeech> getDebateHistory() {
        return get(DEBATE_HISTORY).orElseGet(Collections::emptyList);
    }

    public void setDebateHistory(List<DebateSpeech> debateHistory) {
        set(DEBATE_HISTORY, debateHistory);
    }

    public Map<String, Object> getArguments() {
        return get(ARGUMENTS).orElseGet(LinkedHashMap::new);
    }

    public void setArguments(Map<String, Object> arguments) {
        set(ARGUMENTS, arguments);
    }

    public boolean isConsensusReached() {
        return Boolean.TRUE.equals(get(CONSENSUS_REACHED, false));
    }

    public void setConsensusReached(boolean consensusReached) {
        set(CONSENSUS_REACHED, consensusReached);
    }

    public String getConsensusSummary() {
        return get(CONSENSUS_SUMMARY, "");
    }

    public void setConsensusSummary(String consensusSummary) {
        set(CONSENSUS_SUMMARY, consensusSummary);
    }

    public String getFinalVerdict() {
        return get(FINAL_VERDICT, "");
    }

    public void setFinalVerdict(String finalVerdict) {
        set(FINAL_VERDICT, finalVerdict);
    }

    public String getFinalOutput() {
        return get(FINAL_OUTPUT, "");
    }

    public void setFinalOutput(String finalOutput) {
        set(FINAL_OUTPUT, finalOutput);
    }

    public boolean isWatchdogTripped() {
        return Boolean.TRUE.equals(get(WATCHDOG_TRIPPED, false));
    }

    public void setWatchdogTripped(boolean watchdogTripped) {
        set(WATCHDOG_TRIPPED, watchdogTripped);
    }
}
