package io.github.atengk.boot.search.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 搜索引擎深度游标 (SearchAfter) 滚动结果模型
 * <p>
 * 封装单批次命中文档列表及下一次滚动所需的排序凭据 {@code nextSortValues}。
 *
 * @param <T> 文档实体泛型
 * @author Ateng
 * @since 2026-10-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchScrollResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 命中文档载荷列表
     */
    @Builder.Default
    private List<SearchHit<T>> hits = Collections.emptyList();

    /**
     * 下一次游标滚动所需的排序凭据值列表（若为最后一页或无更多数据则为 null）
     */
    private List<String> nextSortValues;

    /**
     * 获取下一次游标滚动所需的排序凭据数组（等价于 nextSortValues.toArray()）
     *
     * @return 排序凭据对象数组，无更多数据时为 null
     */
    public Object[] getNextSortValuesArray() {
        if (nextSortValues == null || nextSortValues.isEmpty()) {
            return null;
        }
        return nextSortValues.toArray(new Object[0]);
    }

    /**
     * 是否还有下一页数据
     */
    private boolean hasMore;

    /**
     * 提取纯文档实体列表
     *
     * @return 文档实体列表
     */
    public List<T> getDataList() {
        if (hits == null || hits.isEmpty()) {
            return Collections.emptyList();
        }
        return hits.stream()
                .map(SearchHit::getContent)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 构造空游标结果对象
     *
     * @param <T> 实体类型
     * @return 空结果
     */
    public static <T> SearchScrollResult<T> empty() {
        return SearchScrollResult.<T>builder()
                .hits(Collections.emptyList())
                .nextSortValues(null)
                .hasMore(false)
                .build();
    }
}
