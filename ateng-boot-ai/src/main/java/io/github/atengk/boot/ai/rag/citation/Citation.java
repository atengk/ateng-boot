package io.github.atengk.boot.ai.rag.citation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化知识引用证据模型 (Citation)
 * 记录回答内容与知识库检索切片之间的显式溯源关系
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Citation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 引用序号 (1-based index，对应生成回答中的 [1], [2] 锚点标记)
     */
    private Integer index;

    /**
     * 关联切片唯一标识
     */
    private String segmentId;

    /**
     * 来源文档名称 (例如 "用户手册.pdf" 或 "架构规范.md")
     */
    private String docName;

    /**
     * 引用的知识库切片文本片段
     */
    private String chunkContent;

    /**
     * 检索相似度或重排置信度得分 (0.0 ~ 1.0)
     */
    private Double similarityScore;

    /**
     * 切片扩展元数据映射
     */
    @Builder.Default
    private Map<String, Object> metadata = Collections.emptyMap();

    public Map<String, Object> getMetadata() {
        return metadata != null ? metadata : Collections.emptyMap();
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : Collections.emptyMap();
    }
}
