package io.github.atengk.boot.ai.graph.supervisor;

/**
 * 专工 Worker 任务处理器函数式接口
 * <p>
 * 封装各垂直领域专工 Agent（如研究员、开发员、审查员）的执行逻辑，
 * 接收当前状态快照与主管下发的具体指令，产出执行结果供主管汇总与状态机更新。
 * </p>
 *
 * @param <S> 状态模型类型，继承自 {@link SupervisorState}
 * @author Ateng
 * @since 2026-11-06
 */
@FunctionalInterface
public interface WorkerHandler<S extends SupervisorState> {

    /**
     * 执行专工业务逻辑
     *
     * @param state 当前状态快照
     * @param instructions 主管指派给当前专工的具体指令
     * @return 专工执行产出（支持返回 String、领域对象，或返回 Map 增量补丁自动合并展开至状态机）
     * @throws Exception 执行异常
     */
    Object handle(S state, String instructions) throws Exception;
}
