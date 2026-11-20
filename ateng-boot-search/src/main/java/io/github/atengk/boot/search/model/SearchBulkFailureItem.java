package io.github.atengk.boot.search.model;

import io.github.atengk.boot.search.bulk.SearchBulkAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 批量局部死信条目包装模型
 * <p>
 * 绑定触发局部失败的原始入队操作 {@link SearchBulkAction} 与引擎返回的失败明细 {@link SearchBulkFailure}，
 * 供 {@link io.github.atengk.boot.search.bulk.BulkDeadLetterHandler#onItemFailure} 精准重放或死信落库。
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchBulkFailureItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 触发失败的原始批量操作项（可能为 null，仅在无法通过索引与文档 ID 精确配对时发生）
     */
    private SearchBulkAction action;

    /**
     * 搜索引擎返回的单条文档失败明细
     */
    private SearchBulkFailure failure;
}
