package io.github.atengk.boot.sample.flyway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 指定目标数据源操作请求 DTO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TargetOperationRequest {

    /**
     * 目标数据源标识（如 master、log_db、tenant_1001）
     */
    @NotBlank(message = "目标数据源标识 (targetKey) 不能为空")
    private String targetKey;
}
