package io.github.atengk.boot.core.pipeline;

/**
 * 可中断业务管道上下文标记接口
 * 允许上下文在流转中主动触发中断标记，管道引擎在各阶段执行前后将自动探测并安全短路
 *
 * @author Ateng
 * @since 2026-10-27
 */
public interface PipelineInterruptible {

    /**
     * 判断当前上下文是否已被显式触发中断
     *
     * @return 若已中断则返回 true
     */
    boolean isInterrupted();

    /**
     * 获取中断的具体原因说明
     *
     * @return 中断提示文本；若未设置则返回默认原因
     */
    default String getInterruptReason() {
        return "Pipeline execution interrupted";
    }

    /**
     * 主动触发当前上下文中断并记录业务原因
     *
     * @param reason 导致中断的业务原因说明
     */
    void interrupt(String reason);

    /**
     * 主动触发当前上下文中断（采用默认说明）
     */
    default void interrupt() {
        interrupt("Pipeline execution interrupted");
    }
}
