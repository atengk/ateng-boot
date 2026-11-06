package io.github.atengk.boot.ai.graph.pipeline;

import io.github.atengk.boot.ai.graph.BaseGraphState;

/**
 * 流水线阶段任务处理器函数式接口
 * <p>
 * 封装确定性顺序流水线中各业务阶段的执行逻辑。
 * 接收当前状态快照，产出执行结果供流水线汇总与状态机更新。
 * </p>
 *
 * @param <S> 强类型状态模型，继承自 {@link BaseGraphState}
 * @author Ateng
 * @since 2026-11-06
 */
@FunctionalInterface
public interface StageHandler<S extends BaseGraphState> {

    /**
     * 执行流水线阶段业务逻辑
     *
     * @param state 当前状态快照
     * @return 阶段产出结果（支持普通实体对象、字符串，或返回 Map 增量补丁自动合并展开至状态机）
     * @throws Exception 执行异常
     */
    Object handle(S state) throws Exception;
}
