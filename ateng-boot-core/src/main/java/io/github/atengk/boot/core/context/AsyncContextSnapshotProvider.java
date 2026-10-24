package io.github.atengk.boot.core.context;

/**
 * 异步全链路上下文快照扩展提供者 SPI
 * 允许下游模块（如数据源、分布式事务、会话上下文等）无侵入挂载附加维度上下文，
 * 实现五维及未来 N 维上下文快照在受管线程池中的无缝透传与现场安全恢复
 *
 * @author Ateng
 * @since 2026-10-24
 */
public interface AsyncContextSnapshotProvider {

    /**
     * 捕获当前调用父线程的上下文快照
     *
     * @return 当前线程的上下文快照对象；若当前无上下文可返回 null
     */
    Object capture();

    /**
     * 捕获工作线程执行前的原始上下文现场（用于在执行后精准还原现场）
     *
     * @return 工作线程执行前的原始上下文现场
     */
    default Object capturePrevious() {
        return capture();
    }

    /**
     * 在异步工作线程中应用父线程的上下文快照
     *
     * @param snapshot 待应用的上下文快照
     */
    void apply(Object snapshot);

    /**
     * 在 finally 块中还原工作线程执行前的上下文现场，杜绝线程池污染
     *
     * @param previous 工作线程原有的上下文现场
     */
    void restore(Object previous);
}
