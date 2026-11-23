package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 知识库切片入库结果 VO
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRagIngestVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 向量存储分配的唯一主键 ID
     */
    private String id;

    /**
     * 入库内容文本
     */
    private String text;

    /**
     * 绑定的业务元数据
     */
    private Map<String, String> metadata;
}
