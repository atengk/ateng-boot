package io.github.atengk.boot.search.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * 强类型嵌套/折叠内部命中文档模型 (InnerHits)
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchInnerHit implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 所属文档主键 ID
     */
    private String id;

    /**
     * 所属物理索引名称
     */
    private String index;

    /**
     * 内部命中相关性得分
     */
    private Double score;

    /**
     * 内部命中文档源属性字典（保底返回空 Map，严禁返回 null）
     */
    @Builder.Default
    private Map<String, Object> source = Collections.emptyMap();

    /**
     * 获取内部命中文档源属性字典（保底返回空 Map）
     *
     * @return 文档源属性字典
     */
    public Map<String, Object> getSource() {
        return source != null ? source : Collections.emptyMap();
    }
}
