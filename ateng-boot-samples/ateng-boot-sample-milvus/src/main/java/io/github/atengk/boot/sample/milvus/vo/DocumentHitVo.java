package io.github.atengk.boot.sample.milvus.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 向量检索命中视图对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentHitVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 命中文档主键 ID
     */
    private Object id;

    /**
     * 相似度得分 / 距离
     */
    private Float score;

    /**
     * 强类型文档实体内容
     */
    private DocumentVo document;
}
