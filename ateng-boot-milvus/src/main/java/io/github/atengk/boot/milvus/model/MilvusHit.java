package io.github.atengk.boot.milvus.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * Milvus 向量相似度检索标准化命中结果模型
 *
 * @param <T> 实体类型泛型
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MilvusHit<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 命中文档的主键 ID
     */
    private Object id;

    /**
     * 相似度得分 / 距离值 (Score / Distance)
     */
    private Float score;

    /**
     * 强类型反序列化后的业务领域实体 (若使用 Map 模式则此属性为 null，直接从 rawData 读取)
     */
    private T entity;

    /**
     * 原始标量与动态属性字典
     */
    @Builder.Default
    private Map<String, Object> rawData = Collections.emptyMap();

    /**
     * 从原始字典便捷获取指定属性
     *
     * @param key 属性键名
     * @param <V> 值泛型
     * @return 属性值
     */
    @SuppressWarnings("unchecked")
    public <V> V getField(String key) {
        if (rawData == null) {
            return null;
        }
        return (V) rawData.get(key);
    }
}
