package io.github.atengk.boot.milvus.properties;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.github.atengk.boot.core.enums.IBaseEnum;
import io.milvus.v2.common.ConsistencyLevel;
import lombok.Getter;

/**
 * Milvus 向量数据读写一致性级别枚举
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Getter
public enum MilvusConsistencyLevel implements IBaseEnum<String> {

    /**
     * 强一致性 (Strong Consistency)
     * 保证读取到最新鲜写入的数据，写后即查 100% 可见，延迟相对稍高。
     */
    STRONG("STRONG", "强一致性", ConsistencyLevel.STRONG),

    /**
     * 有界时限一致性 (Bounded Consistency，默认)
     * 允许存在微小容忍时间差（通常数十毫秒），兼顾极高吞吐与读写近似实时性。
     */
    BOUNDED("BOUNDED", "有界时限一致性", ConsistencyLevel.BOUNDED),

    /**
     * 最终一致性 (Eventually Consistency)
     * 优先保障极致检索吞吐与并发性能，数据同步存在毫秒级异步延迟。
     */
    EVENTUALLY("EVENTUALLY", "最终一致性", ConsistencyLevel.EVENTUALLY);

    @JsonValue
    private final String code;

    private final String label;

    private final ConsistencyLevel nativeLevel;

    MilvusConsistencyLevel(String code, String label, ConsistencyLevel nativeLevel) {
        this.code = code;
        this.label = label;
        this.nativeLevel = nativeLevel;
    }

    /**
     * 根据编码或枚举名称解析一致性级别（忽略大小写，未知非空值快速失败）
     *
     * @param value 一致性级别编码或枚举名
     * @return 匹配的枚举实例，入参为 null 或空白时返回 null
     * @throws IllegalArgumentException 当传入无法识别的一致性级别编码时抛出
     */
    @JsonCreator
    public static MilvusConsistencyLevel fromValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        for (MilvusConsistencyLevel level : values()) {
            if (level.code.equalsIgnoreCase(text) || level.name().equalsIgnoreCase(text)) {
                return level;
            }
        }
        throw new IllegalArgumentException("不支持的 Milvus 一致性级别: " + text);
    }
}
