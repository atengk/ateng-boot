package io.github.atengk.boot.sample.ai.model;

import io.github.atengk.boot.ai.graph.TypedKey;
import io.github.atengk.boot.ai.graph.debate.DebateState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 架构评审对抗式辩论强类型状态契约模型
 * <p>
 * 继承自 {@link DebateState}，收敛架构方案标题、背景描述、目标 QPS、生产就绪评分、
 * 质疑人审查意见以及正方修订补丁等业务属性。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class ArchitectureDebateState extends DebateState {

    /**
     * 架构方案标题
     */
    public static final TypedKey<String> PROPOSAL_TITLE = TypedKey.of("proposalTitle", String.class);

    /**
     * 方案详细设计说明
     */
    public static final TypedKey<String> PROPOSAL_DESC = TypedKey.of("proposalDesc", String.class);

    /**
     * 目标峰值并发 QPS
     */
    public static final TypedKey<Integer> TARGET_QPS = TypedKey.of("targetQps", Integer.class);

    /**
     * 架构就绪度评分 (0-100)
     */
    public static final TypedKey<Integer> READINESS_SCORE = TypedKey.of("readinessScore", Integer.class);

    /**
     * 审查员质疑意见
     */
    public static final TypedKey<String> CRITIC_OPINION = TypedKey.of("criticOpinion", String.class);

    /**
     * 是否故意制造死锁不妥协 (用于测试看门狗熔断)
     */
    public static final TypedKey<Boolean> FORCE_DEADLOCK = TypedKey.of("forceDeadlock", Boolean.class);

    /**
     * 无参缺省构造器
     */
    public ArchitectureDebateState() {
        this(new LinkedHashMap<>());
    }

    /**
     * 基于初始字典构造状态模型
     *
     * @param initData 初始状态字典
     */
    public ArchitectureDebateState(Map<String, Object> initData) {
        super(initData);
    }

    public String getProposalTitle() {
        return get(PROPOSAL_TITLE, "");
    }

    public void setProposalTitle(String proposalTitle) {
        set(PROPOSAL_TITLE, proposalTitle);
    }

    public String getProposalDesc() {
        return get(PROPOSAL_DESC, "");
    }

    public void setProposalDesc(String proposalDesc) {
        set(PROPOSAL_DESC, proposalDesc);
    }

    public Integer getTargetQps() {
        return get(TARGET_QPS, 1000);
    }

    public void setTargetQps(Integer targetQps) {
        set(TARGET_QPS, targetQps);
    }

    public Integer getReadinessScore() {
        return get(READINESS_SCORE, 0);
    }

    public void setReadinessScore(Integer readinessScore) {
        set(READINESS_SCORE, readinessScore);
    }

    public String getCriticOpinion() {
        return get(CRITIC_OPINION, "");
    }

    public void setCriticOpinion(String criticOpinion) {
        set(CRITIC_OPINION, criticOpinion);
    }

    public boolean isForceDeadlock() {
        return Boolean.TRUE.equals(get(FORCE_DEADLOCK, false));
    }

    public void setForceDeadlock(boolean forceDeadlock) {
        set(FORCE_DEADLOCK, forceDeadlock);
    }
}
