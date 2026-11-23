package io.github.atengk.boot.sample.ai.workflow;

import io.github.atengk.boot.ai.graph.debate.DebateConsensusWorkflow;
import io.github.atengk.boot.ai.graph.debate.DebateParticipant;
import io.github.atengk.boot.ai.graph.debate.DebateState;
import io.github.atengk.boot.sample.ai.model.ArchitectureDebateState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 架构方案技术选型红蓝对抗评审认知工作流
 * <p>
 * 纳管架构发起人 (proposer) 与红蓝对抗安全官 (critic) 进行深度博弈，
 * 裁决员 (arbiter) 负责每轮评定架构生产就绪评分是否收敛并达成放行共识。
 * 内置 3 轮最大辩论硬看门狗限制，防御方案分歧死锁发散。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Component
public class ArchitectureReviewDebateWorkflow extends DebateConsensusWorkflow<ArchitectureDebateState> {

    public static final String WORKFLOW_NAME = "architectureReviewDebateWorkflow";

    public static final String PARTICIPANT_PROPOSER = "proposer";
    public static final String PARTICIPANT_CRITIC = "critic";

    public ArchitectureReviewDebateWorkflow() {
        super(WORKFLOW_NAME, ArchitectureDebateState::new);
    }

    @Override
    protected int maxDebateTurns() {
        return 3;
    }

    @Override
    protected List<DebateParticipant<ArchitectureDebateState>> registerDebaters() {
        return List.of(
                // 辩手 1：正方架构方案发起人
                DebateParticipant.of(PARTICIPANT_PROPOSER, "AFFIRMATIVE", "架构方案设计发起人", (state, round) -> {
                    log.info("[DEBATE-WORKFLOW] 架构发起人开始第 {} 轮发言, 方案: {}", round, state.getProposalTitle());

                    if (round == 1) {
                        return Map.of(
                                ArchitectureDebateState.READINESS_SCORE.name(), 65,
                                "speechText", String.format("提出初步架构方案 [%s]: 目标 QPS %d，采用单机 Redis 缓存支撑核心读流量",
                                        state.getProposalTitle(), state.getTargetQps())
                        );
                    } else {
                        if (state.isForceDeadlock()) {
                            return Map.of(
                                    ArchitectureDebateState.READINESS_SCORE.name(), 60,
                                    "speechText", "坚持原有单机轻量设计，拒绝引入集群与熔断复杂性"
                            );
                        } else {
                            return Map.of(
                                    ArchitectureDebateState.READINESS_SCORE.name(), 92,
                                    "speechText", String.format("针对第 %d 轮质疑进行方案补强: 增加 Redis Sentinel 集群、本地 Caffeine 多级缓存与 Sentinel 熔断降级",
                                            round - 1)
                            );
                        }
                    }
                }),

                // 辩手 2：反方架构与安全挑战人
                DebateParticipant.of(PARTICIPANT_CRITIC, "NEGATIVE", "红蓝对抗安全与高可用审查官", (state, round) -> {
                    int score = state.getReadinessScore();
                    log.info("[DEBATE-WORKFLOW] 审查官开始第 {} 轮质询审查, 当前就绪度评分: {}", round, score);

                    if (score < 85) {
                        String opinion = String.format("当前方案评分 %d 处于高危区间: 单机 Redis 存在单点故障风险且缺乏防击穿限流保护，驳回准入", score);
                        return Map.of(
                                ArchitectureDebateState.CRITIC_OPINION.name(), opinion,
                                "criticApproved", false,
                                "speechText", opinion
                        );
                    } else {
                        String opinion = String.format("补充的多级缓存与集群高可用设计完备，就绪度评分达 %d，达到生产准入基线，同意放行", score);
                        return Map.of(
                                ArchitectureDebateState.CRITIC_OPINION.name(), opinion,
                                "criticApproved", true,
                                "speechText", opinion
                        );
                    }
                })
        );
    }

    @Override
    protected boolean checkConsensus(ArchitectureDebateState state) {
        return Boolean.TRUE.equals(state.data().get("criticApproved"));
    }

    @Override
    protected Map<String, Object> adjudicate(ArchitectureDebateState state) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(DebateState.FINAL_VERDICT.name(), "APPROVED");
        patch.put(DebateState.CONSENSUS_SUMMARY.name(),
                String.format("方案在第 %d 轮博弈后收敛分歧，各方对高可用多级缓存架构达成一致，就绪评分: %d",
                        state.getRoundCount(), state.getReadinessScore()));
        patch.put(DebateState.FINAL_OUTPUT.name(),
                String.format("架构方案 [%s] 经对抗评审全票通过，准入放行进入实施阶段", state.getProposalTitle()));
        return patch;
    }

    @Override
    protected Map<String, Object> onWatchdogFallback(ArchitectureDebateState state) {
        Map<String, Object> patch = super.onWatchdogFallback(state);
        patch.put(DebateState.FINAL_VERDICT.name(), "FORCED_ARBITRATION");
        patch.put(DebateState.CONSENSUS_SUMMARY.name(),
                String.format("对抗博弈达到最大轮次上限 (%d 轮) 仍未达成共识，裁决员介入强制仲裁并下发整改清单", maxDebateTurns()));
        patch.put(DebateState.FINAL_OUTPUT.name(),
                String.format("架构方案 [%s] 触发看门狗熔断强制裁决: 限期按生产规范补齐 Sentinel 与高可用集群后再次提审", state.getProposalTitle()));
        return patch;
    }
}
