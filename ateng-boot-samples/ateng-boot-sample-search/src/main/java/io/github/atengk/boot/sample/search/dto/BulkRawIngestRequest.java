package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 原始键值映射流式入库请求 DTO（专用于演示非法字段类型触发局部死信拦截）
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkRawIngestRequest {

    /**
     * 文档唯一标识 ID
     */
    @NotBlank(message = "文档ID不能为空")
    private String id;

    /**
     * 分片路由键（可选）
     */
    private String routing;

    /**
     * 待写入文档键值载荷
     */
    @NotEmpty(message = "文档字段载荷不能为空")
    private Map<String, Object> fields;
}
