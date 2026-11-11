package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量写入请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkIngestRequest {

    @NotEmpty(message = "待批量写入文档列表不能为空")
    @Valid
    private List<ProductSaveRequest> items;
}
