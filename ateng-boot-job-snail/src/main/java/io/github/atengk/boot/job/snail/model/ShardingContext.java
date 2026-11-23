package io.github.atengk.boot.job.snail.model;

import com.aizuda.snailjob.client.job.core.executor.JobContextManager;
import com.aizuda.snailjob.common.core.model.JobContext;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;

import java.io.Serializable;
import java.util.Objects;

/**
 * SnailJob 分布式任务分片上下文模型
 *
 * <p>封装当前执行节点的分片序号（从 0 开始）与总分片数（大于等于 1），支持在任务方法中
 * 声明为形参自动注入或通过静态方法 {@link #current()} 随处获取。
 * 提供标准取模匹配与边界判定方法，杜绝多节点协同中的漏单或重复计算。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class ShardingContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前分片序号（从 0 开始）
     */
    private final int shardIndex;

    /**
     * 总分片数（大于等于 1）
     */
    private final int shardTotal;

    public ShardingContext(int shardIndex, int shardTotal) {
        this.shardIndex = Math.max(0, shardIndex);
        this.shardTotal = Math.max(1, shardTotal);
    }

    /**
     * 从当前 SnailJob 调度现场提取分片参数
     * <p>优先从 {@link SnailJobContextHolder} 提取；若未就绪则回退至官方 {@link JobContextManager}；
     * 若仍未在调度上下文中运行，保底返回单机分片（shardIndex=0, shardTotal=1）。</p>
     *
     * @return 分片上下文实例
     */
    public static ShardingContext current() {
        if (SnailJobContextHolder.get() != null && SnailJobContextHolder.get().getSharding() != null) {
            return SnailJobContextHolder.get().getSharding();
        }
        try {
            JobContext nativeContext = JobContextManager.getJobContext();
            if (nativeContext != null && nativeContext.getShardingTotal() != null) {
                int index = nativeContext.getShardingIndex() != null ? nativeContext.getShardingIndex() : 0;
                int total = nativeContext.getShardingTotal() != null ? nativeContext.getShardingTotal() : 1;
                return new ShardingContext(index, total);
            }
        } catch (Throwable ignored) {
        }
        return new ShardingContext(0, 1);
    }

    /**
     * 是否处于多节点分布式分片模式
     *
     * @return true 若总分片数大于 1
     */
    public boolean isSharded() {
        return shardTotal > 1;
    }

    /**
     * 是否为首个分片（序号为 0）
     *
     * @return true 若为首个分片
     */
    public boolean isFirst() {
        return shardIndex == 0;
    }

    /**
     * 是否为末尾分片
     *
     * @return true 若为末尾分片
     */
    public boolean isLast() {
        return shardIndex >= shardTotal - 1;
    }

    /**
     * 判定指定数值 ID 是否路由到当前分片（基于非负余数取模算法）
     *
     * @param id 业务主键或数值分片键
     * @return true 若属于当前分片执行范围
     */
    public boolean match(long id) {
        if (shardTotal <= 1) {
            return true;
        }
        return Math.floorMod(id, (long) shardTotal) == shardIndex;
    }

    /**
     * 判定指定字符串分片键是否路由到当前分片（基于字符串 Hash 值的非负余数取模算法）
     *
     * @param key 业务分片键字符串
     * @return true 若属于当前分片执行范围
     */
    public boolean match(String key) {
        if (shardTotal <= 1) {
            return true;
        }
        if (key == null) {
            return shardIndex == 0;
        }
        return Math.floorMod(key.hashCode(), shardTotal) == shardIndex;
    }

    public int getShardIndex() {
        return shardIndex;
    }

    public int getShardTotal() {
        return shardTotal;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ShardingContext that = (ShardingContext) o;
        return shardIndex == that.shardIndex && shardTotal == that.shardTotal;
    }

    @Override
    public int hashCode() {
        return Objects.hash(shardIndex, shardTotal);
    }

    @Override
    public String toString() {
        return "ShardingContext{" +
                "shardIndex=" + shardIndex +
                ", shardTotal=" + shardTotal +
                '}';
    }
}
