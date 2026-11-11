package io.github.atengk.boot.sample.search.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 服务端数据重索引请求 DTO (Reindex)
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReindexRequest {

    @NotBlank(message = "源索引名称不能为空")
    private String sourceIndex;

    @NotBlank(message = "目标索引名称不能为空")
    private String targetIndex;

    /**
     * 可选时间过滤边界（若传入则执行增量条件重索引）
     */
    private LocalDateTime minTime;
}
