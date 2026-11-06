package io.github.atengk.boot.ai.properties;

import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * 跨会话原子化长效情景语义记忆强类型配置属性
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
public class EpisodicMemoryProperties {

    /**
     * 是否启用跨会话长效情景语义记忆基础设施
     */
    private boolean enabled = true;

    /**
     * 默认语义召回的最大事实条数 (Top-K)
     */
    private int maxRecallResults = 5;

    /**
     * 语义检索默认最低相似度阈值 (0.0 ~ 1.0)
     */
    private Double minScore = 0.6;

    /**
     * 指定用于长效事实抽取的轻量模型标识别名 (若为空则自动使用默认主模型)
     */
    private String extractModel;

    /**
     * 专属长效情景记忆向量数据库配置 (若为空则自动复用 RAG 知识库配置或默认单机内存库)
     */
    @NestedConfigurationProperty
    private VectorStoreProperties store;
}
