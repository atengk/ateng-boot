package io.github.atengk.boot.ai.graph.parallel;

import io.github.atengk.boot.ai.graph.BaseGraphState;

/**
 * 并行扇出认知分支任务处理器函数式接口
 *
 * @param <S> 强类型状态模型，继承自 {@link BaseGraphState}
 * @author Ateng
 * @since 2026-11-22
 */
@FunctionalInterface
public interface BranchHandler<S extends BaseGraphState> {

    /**
     * 在受管异步工作线程（已透传四维上下文）中执行单个并行分支逻辑
     *
     * @param state 扇出前状态快照只读视图
     * @return 分支计算产出结果
     * @throws Exception 分支执行异常
     */
    Object handle(S state) throws Exception;
}
