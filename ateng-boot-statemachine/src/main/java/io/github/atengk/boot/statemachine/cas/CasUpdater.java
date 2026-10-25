package io.github.atengk.boot.statemachine.cas;

/**
 * 状态机 CAS (Compare-And-Swap) 乐观锁更新器函数式接口
 *
 * @param <T> 业务实体类型
 * @param <S> 业务状态类型
 * @author Ateng
 * @since 2026-10-25
 */
@FunctionalInterface
public interface CasUpdater<T, S> {

    /**
     * 执行数据库持久化 CAS 乐观锁条件更新
     * <p>对应 SQL 范式: {@code UPDATE table SET status = :targetState WHERE id = :id AND status = :sourceState}</p>
     *
     * @param entity      业务实体
     * @param sourceState 当前期望源状态 (CAS 条件: status = sourceState)
     * @param targetState 跃迁后目标状态 (新值: status = targetState)
     * @return 数据库受影响行数 (通常为 1 表示成功，0 表示并发冲突更新失败)
     */
    int updateWithCas(T entity, S sourceState, S targetState);
}
