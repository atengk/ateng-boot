package io.github.atengk.boot.sample.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量死信回调统计观测响应 VO
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkDeadLetterStatsResponse {

    /**
     * 整批异常触发死信累计次数
     */
    private Long batchFailureCount;

    /**
     * 局部文档失败触发死信累计次数
     */
    private Long itemFailureCount;

    /**
     * 近期死信处理摘要列表
     */
    private List<String> recentMessages;
}
