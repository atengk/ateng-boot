package io.github.atengk.boot.search.model;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.io.Serializable;

/**
 * 强类型不可变索引运行期 Settings 视图模型（与底层 Elasticsearch 客户端完全解耦）
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Value
@Builder
@Jacksonized
public class SearchIndexSettings implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主分片数量
     */
    String numberOfShards;

    /**
     * 副本分片数量
     */
    String numberOfReplicas;

    /**
     * 索引刷新间隔表达式（如 {@code 1s}、{@code 5s}、{@code -1}）
     */
    String refreshInterval;

    /**
     * 深分页最大返回窗口大小 ({@code max_result_window})
     */
    Integer maxResultWindow;
}
