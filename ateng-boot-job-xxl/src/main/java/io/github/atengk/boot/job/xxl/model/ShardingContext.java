package io.github.atengk.boot.job.xxl.model;

import com.xxl.job.core.context.XxlJobContext;

import java.io.Serializable;
import java.util.Objects;

/**
 * XXL-Job 分布式任务分片上下文对象
 *
 * <p>封装当前执行节点的任务分片参数（当前分片序号与总分片数），支持在 {@code @XxlJob} 或 {@code @AtengXxlJob}
 * 方法中直接声明为入参自动注入，并提供分片匹配与首尾节点辅助判定方法。</p>
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
     * 从当前 XXL-Job 调度上下文提取分片参数
     * 若未在 XXL-Job 上下文中运行，保底返回单机分片（shardIndex=0, shardTotal=1）
     *
     * @return 分片上下文实例
     */
    public static ShardingContext current() {
        XxlJobContext context = XxlJobContext.getXxlJobContext();
        if (context != null) {
            return new ShardingContext(context.getShardIndex(), context.getShardTotal());
        }
        return new ShardingContext(0, 1);
    }

    /**
     * 是否处于多节点分片调度模式
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
     * @param id 业务主键或分片键数值
     * @return true 若属于当前分片执行范围
     */
    public boolean match(long id) {
        if (shardTotal <= 1) {
            return true;
        }
        return Math.floorMod(id, (long) shardTotal) == shardIndex;
    }

    /**
     * 判定指定字符串分片键是否路由到当前分片（基于哈希非负余数取模算法）
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
