package io.github.atengk.boot.ai.graph.debate;

import io.github.atengk.boot.ai.graph.BaseGraphState;

/**
 * 辩手角色论辩执行处理器函数式接口
 * <p>
 * 封装对抗式认知状态图中各立场辩手（如正方提案人、反方质询人、红蓝对抗安全官）在特定轮次的论辩逻辑。
 * 接收当前状态快照与当前辩论轮次，产出本轮论点或增量状态补丁。
 * </p>
 *
 * @param <S> 强类型状态模型，继承自 {@link BaseGraphState}
 * @author Ateng
 * @since 2026-11-06
 */
@FunctionalInterface
public interface DebateHandler<S extends BaseGraphState> {

    /**
     * 执行当前轮次论辩逻辑
     *
     * @param state 当前状态模型快照
     * @param round 当前辩论轮次计数 (从 1 开始递增)
     * @return 论辩产出（支持字符串论点、结构化领域对象，或返回 Map 增量补丁自动合并展开至状态机）
     * @throws Exception 业务执行异常
     */
    Object handle(S state, int round) throws Exception;
}
