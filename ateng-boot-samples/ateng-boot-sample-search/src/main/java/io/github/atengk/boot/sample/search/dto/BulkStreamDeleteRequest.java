package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式批量路由删除请求 DTO
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkStreamDeleteRequest {

    /**
     * 待删除文档 ID
     */
    @NotBlank(message = "文档ID不能为空")
    private String id;

    /**
     * 分片路由键（可选）
     */
    private String routing;
}
