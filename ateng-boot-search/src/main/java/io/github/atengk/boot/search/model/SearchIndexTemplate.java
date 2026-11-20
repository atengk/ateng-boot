package io.github.atengk.boot.search.model;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 强类型不可变索引模板定义视图模型（与底层 Elasticsearch 客户端完全解耦）
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Value
@Builder
@Jacksonized
public class SearchIndexTemplate implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 索引模板名称
     */
    String name;

    /**
     * 匹配的索引通配模式列表
     */
    @Builder.Default
    List<String> indexPatterns = Collections.emptyList();

    /**
     * 引用的组件模板名称列表
     */
    @Builder.Default
    List<String> composedOf = Collections.emptyList();

    /**
     * 模板匹配优先级（数值越大越优先）
     */
    Long priority;

    /**
     * 模板版本号
     */
    Long version;

    /**
     * 获取匹配的索引模式列表（保底返回不可变空集合）
     *
     * @return 索引模式列表
     */
    public List<String> getIndexPatterns() {
        return indexPatterns != null ? Collections.unmodifiableList(indexPatterns) : Collections.emptyList();
    }

    /**
     * 获取引用的组件模板列表（保底返回不可变空集合）
     *
     * @return 组件模板名称列表
     */
    public List<String> getComposedOf() {
        return composedOf != null ? Collections.unmodifiableList(composedOf) : Collections.emptyList();
    }
}
