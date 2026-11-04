package io.github.atengk.boot.milvus.properties;

import io.milvus.v2.common.ConsistencyLevel;

/**
 * Milvus 向量数据读写一致性级别枚举
 *
 * @author Ateng
 * @since 2026-11-04
 */
public enum MilvusConsistencyLevel {

    /**
     * 强一致性 (Strong Consistency)
     * 保证读取到最新鲜写入的数据，写后即查 100% 可见，延迟相对稍高。
     */
    STRONG(ConsistencyLevel.STRONG),

    /**
     * 有界时限一致性 (Bounded Consistency，默认)
     * 允许存在微小容忍时间差（通常数十毫秒），兼顾极高吞吐与读写近似实时性。
     */
    BOUNDED(ConsistencyLevel.BOUNDED),

    /**
     * 最终一致性 (Eventually Consistency)
     * 优先保障极致检索吞吐与并发性能，数据同步存在毫秒级异步延迟。
     */
    EVENTUALLY(ConsistencyLevel.EVENTUALLY);

    private final ConsistencyLevel nativeLevel;

    MilvusConsistencyLevel(ConsistencyLevel nativeLevel) {
        this.nativeLevel = nativeLevel;
    }

    /**
     * 获取 Milvus 官方原生 ConsistencyLevel 对象
     *
     * @return 官方原生 ConsistencyLevel
     */
    public ConsistencyLevel getNativeLevel() {
        return nativeLevel;
    }
}
