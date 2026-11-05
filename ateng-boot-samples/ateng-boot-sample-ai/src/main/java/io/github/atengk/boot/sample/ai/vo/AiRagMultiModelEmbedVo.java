package io.github.atengk.boot.sample.ai.vo;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 多模型命名 Embedding 计算响应载体
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Value
@Builder
public class AiRagMultiModelEmbedVo {

    /**
     * 原始输入文本
     */
    String text;

    /**
     * 统计模型总数
     */
    int totalModels;

    /**
     * 各模型计算出的特征向量信息列表
     */
    List<ModelEmbeddingItem> models;

    /**
     * 单个模型的向量计算结果明细
     */
    @Value
    @Builder
    public static class ModelEmbeddingItem {
        String modelName;
        int dimension;
        List<Float> preview;
    }
}
