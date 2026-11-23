package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 文本向量化结果 VO
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagEmbedVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 特征向量维度
     */
    private int dimension;

    /**
     * 向量浮点数预览 (前 5 维示例)
     */
    private List<Float> preview;

    /**
     * 简短摘要说明
     */
    private String summary;
}
