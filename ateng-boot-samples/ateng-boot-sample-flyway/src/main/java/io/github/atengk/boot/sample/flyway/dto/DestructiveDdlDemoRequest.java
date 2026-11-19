package io.github.atengk.boot.sample.flyway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 待执行 SQL 高危破坏性 DDL 静态预检演练请求 DTO
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DestructiveDdlDemoRequest {

    /**
     * 待预检的 SQL 迁移脚本内容
     */
    @NotBlank(message = "待预检 SQL 脚本内容 (sqlScript) 不能为空")
    private String sqlScript;

    /**
     * 是否允许脚本通过 {@code -- @ateng-allow-destructive-ddl} 注释显式豁免放行
     */
    @Builder.Default
    private boolean allowExemptionAnnotation = true;
}
