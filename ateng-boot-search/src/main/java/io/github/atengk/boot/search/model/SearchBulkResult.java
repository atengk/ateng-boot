package io.github.atengk.boot.search.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 搜索引擎批量操作执行结果载荷模型
 * <p>
 * 封装批量处理成功条数、失败条数、耗时毫秒数以及结构化的失败条目明细快照。
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchBulkResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 批量操作成功写入/处理的文档总数
     */
    @Builder.Default
    private int successCount = 0;

    /**
     * 批量操作处理失败的文档总数
     */
    @Builder.Default
    private int failureCount = 0;

    /**
     * 服务端执行耗时（毫秒）
     */
    @Builder.Default
    private Long took = 0L;

    /**
     * 失败条目明细列表（保底非 null 空安全集合）
     */
    @Builder.Default
    private List<SearchBulkFailure> failures = Collections.emptyList();

    /**
     * 获取失败明细列表（保底返回非空集合）
     *
     * @return 失败明细列表
     */
    public List<SearchBulkFailure> getFailures() {
        return failures != null ? failures : Collections.emptyList();
    }

    /**
     * 是否存在失败条目
     *
     * @return true 表示存在失败条目
     */
    public boolean hasFailures() {
        return failureCount > 0 || (failures != null && !failures.isEmpty());
    }

    /**
     * 是否全部处理成功（零失败）
     *
     * @return true 表示全部成功
     */
    public boolean isSuccess() {
        return !hasFailures();
    }
}
