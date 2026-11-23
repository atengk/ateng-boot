package io.github.atengk.boot.sample.openapi.model;

import io.github.atengk.boot.openapi.export.OpenApiFormat;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 契约安全落盘校验响应视图
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Schema(description = "OpenAPI 契约安全落盘校验响应视图")
public class ContractExportResultVO {

    @Schema(description = "导出的业务分组标识", example = "admin")
    private String group;

    @Schema(description = "导出格式", example = "YAML")
    private OpenApiFormat format;

    @Schema(description = "受管临时目录内规范化相对路径", example = "exports/admin-contract.yaml")
    private String relativePath;

    @Schema(description = "写入文件的字节大小", example = "10240")
    private long fileSize;

    @Schema(description = "人类可读文件大小", example = "10.00 KB")
    private String readableSize;

    public ContractExportResultVO() {
    }

    public ContractExportResultVO(String group, OpenApiFormat format, String relativePath, long fileSize, String readableSize) {
        this.group = group;
        this.format = format;
        this.relativePath = relativePath;
        this.fileSize = fileSize;
        this.readableSize = readableSize;
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

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getReadableSize() {
        return readableSize;
    }

    public void setReadableSize(String readableSize) {
        this.readableSize = readableSize;
    }
}
