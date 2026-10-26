package io.github.atengk.boot.search.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 搜索引擎批量写入单条文档失败明细模型
 * <p>
 * 封装在批量操作中发生异常的文档标识、目标索引、HTTP 状态码及底层根因描述。
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchBulkFailure implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标索引名或别名
     */
    private String index;

    /**
     * 失败文档的唯一标识 ID
     */
    private String id;

    /**
     * HTTP 响应状态码（如 400 格式错误、409 版本冲突、500 异常等）
     */
    private Integer status;

    /**
     * 失败异常分类（如 mapping_parsing_exception、version_conflict_engine_exception）
     */
    private String errorType;

    /**
     * 底层失败根因描述
     */
    private String errorReason;
}
