package io.github.atengk.boot.sample.openapi.model;

import io.github.atengk.boot.openapi.export.OpenApiFormat;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 契约摘要预览响应视图
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Schema(description = "OpenAPI 契约摘要预览响应视图")
public class ContractPreviewVO {

    @Schema(description = "业务分组标识", example = "admin")
    private String group;

    @Schema(description = "导出规范格式", example = "JSON")
    private OpenApiFormat format;

    @Schema(description = "完整契约字节大小", example = "12840")
    private int byteLength;

    @Schema(description = "契约内容摘要前缀（最多展示前 512 字符）")
    private String snippet;

    public ContractPreviewVO() {
    }

    public ContractPreviewVO(String group, OpenApiFormat format, int byteLength, String snippet) {
        this.group = group;
        this.format = format;
        this.byteLength = byteLength;
        this.snippet = snippet;
    }

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

    public int getByteLength() {
        return byteLength;
    }

    public void setByteLength(int byteLength) {
        this.byteLength = byteLength;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }
}
