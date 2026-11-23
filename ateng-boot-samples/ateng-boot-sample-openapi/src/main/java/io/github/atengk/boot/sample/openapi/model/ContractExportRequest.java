package io.github.atengk.boot.sample.openapi.model;

import io.github.atengk.boot.openapi.export.OpenApiExportHelper;
import io.github.atengk.boot.openapi.export.OpenApiFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 契约安全落盘校验请求参数
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Schema(description = "OpenAPI 契约安全落盘校验请求参数")
public class ContractExportRequest {

    @NotBlank(message = "分组名称不能为空")
    @Pattern(regexp = OpenApiExportHelper.GROUP_NAME_REGEX, message = "分组名称包含非法字符")
    @Schema(description = "目标业务分组标识", example = "admin")
    private String group = "default";

    @NotNull(message = "导出格式不能为空")
    @Schema(description = "导出文件格式", example = "YAML")
    private OpenApiFormat format = OpenApiFormat.JSON;

    @NotBlank(message = "导出相对路径不能为空")
    @Schema(description = "受管临时目录内的相对导出子路径（禁止含 .. 路径穿越）", example = "exports/admin-contract.yaml")
    private String relativePath;

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public OpenApiFormat getFormat() {
        return format;
    }

    public void setFormat(OpenApiFormat format) {
        this.format = format;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }
}
