package io.github.atengk.boot.sample.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量写入执行结果统计 VO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkSummaryResponse {

    /**
     * 提交写入文档总数
     */
    private Long total;

    /**
     * 写入成功总数
     */
    private Long successfulCount;

    /**
     * 写入失败总数
     */
    private Long failedCount;

    /**
     * 是否存在失败项
     */
    private Boolean hasFailures;

    /**
     * 失败明细消息列表（宽容模式返回）
     */
    private List<String> failureMessages;
}
