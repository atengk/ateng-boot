package io.github.atengk.boot.search.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 搜索引擎强类型文档命中载荷模型
 * <p>
 * 封装文档内容本体及其元数据信息（文档 ID、索引名、匹配打分、OCC 乐观锁凭据 seqNo/primaryTerm、版本号及高亮片段）。
 *
 * @param <T> 文档实体泛型
 * @author Ateng
 * @since 2026-10-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchHit<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文档唯一标识 ID
     */
    private String id;

    /**
     * 所属索引名或别名
     */
    private String index;

    /**
     * 强类型文档反序列化实体本体
     */
    private T content;

    /**
     * 相关度打分（检索得分）
     */
    private Double score;

    /**
     * 乐观并发控制序列号 (Sequence Number)
     */
    private Long seqNo;

    /**
     * 乐观并发控制分片世代 (Primary Term)
     */
    private Long primaryTerm;

    /**
     * 文档内部版本号
     */
    private Long version;

    /**
     * 分片路由键
     */
    private String routing;

    /**
     * 排序凭据值列表（用于深度游标 searchAfter 传递）
     */
    @Builder.Default
    private List<String> sortValues = Collections.emptyList();

    /**
     * 检索高亮匹配片段集合（Key 为字段名，Value 为高亮片段列表）
     */
    @Builder.Default
    private Map<String, List<String>> highlightFields = Collections.emptyMap();

    /**
     * 获取文档实体本体（等价于 getContent）
     *
     * @return 文档实体
     */
    public T getSource() {
        return this.content;
    }

    /**
     * 获取检索高亮匹配片段集合（等价于 getHighlightFields）
     *
     * @return 高亮片段字典
     */
    public Map<String, List<String>> getHighlights() {
        return this.highlightFields != null ? this.highlightFields : Collections.emptyMap();
    }
}
