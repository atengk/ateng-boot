package io.github.atengk.boot.sample.flyway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 基线初始化请求 DTO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BaselineRequest {

    /**
     * 目标数据源标识
     */
    @NotBlank(message = "目标数据源标识 (targetKey) 不能为空")
    private String targetKey;

    /**
     * 基线版本号（可选，为空则继承默认基线版本）
     */
    private String version;

    /**
     * 基线描述文本（可选）
     */
    private String description;
}
