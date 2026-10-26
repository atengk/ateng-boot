package io.github.atengk.boot.search.model;

import io.github.atengk.boot.core.model.PageResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 搜索引擎标准浅分页结果载荷模型
 * <p>
 * 封装分页核心要素与命中文档列表，支持无缝流式转为 Core 模块的 {@link PageResult}。
 *
 * @param <T> 文档实体泛型
 * @author Ateng
 * @since 2026-10-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchPage<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 命中文档载荷列表
     */
    @Builder.Default
    private List<SearchHit<T>> hits = Collections.emptyList();

    /**
     * 总命中记录数
     */
    private long total;

    /**
     * 当前页码（从 1 开始）
     */
    private int pageNum;

    /**
     * 每页大小
     */
    private int pageSize;

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
     * 计算总页数
     *
     * @return 总页数
     */
    public long getTotalPages() {
        if (pageSize <= 0) {
            return 0L;
        }
        return (total + pageSize - 1) / pageSize;
    }

    /**
     * 转为 Core 统一标准分页响应模型（内容为实体对象本体）
     *
     * @return PageResult 实例
     */
    public PageResult<T> toPageResult() {
        return PageResult.of(getDataList(), total, pageNum, pageSize, getTotalPages());
    }

    /**
     * 转为 Core 统一标准分页响应模型（内容保留 SearchHit 完整载荷，包含高亮、打分与版本元数据）
     *
     * @return PageResult 实例
     */
    public PageResult<SearchHit<T>> toHitPageResult() {
        return PageResult.of(hits != null ? hits : Collections.emptyList(), total, pageNum, pageSize, getTotalPages());
    }

    /**
     * 构造空分页对象
     *
     * @param pageNum  页码
     * @param pageSize 每页大小
     * @param <T>      实体类型
     * @return 空分页对象
     */
    public static <T> SearchPage<T> empty(int pageNum, int pageSize) {
        return SearchPage.<T>builder()
                .hits(Collections.emptyList())
                .total(0L)
                .pageNum(pageNum)
                .pageSize(pageSize)
                .build();
    }
}
