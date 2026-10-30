package io.github.atengk.boot.openapi.export;

import org.springframework.http.MediaType;

/**
 * OpenAPI 契约规范离线导出格式枚举
 *
 * @author Ateng
 * @since 2026-10-30
 */
public enum OpenApiFormat {

    /**
     * 标准 JSON 格式
     */
    JSON("json", MediaType.APPLICATION_JSON, "application/json"),

    /**
     * 标准 YAML 格式
     */
    YAML("yaml", MediaType.parseMediaType("application/yaml;charset=UTF-8"), "application/yaml;charset=UTF-8");

    private final String extension;
    private final MediaType mediaType;
    private final String mediaTypeString;

    OpenApiFormat(String extension, MediaType mediaType, String mediaTypeString) {
        this.extension = extension;
        this.mediaType = mediaType;
        this.mediaTypeString = mediaTypeString;
    }

    public String getExtension() {
        return extension;
    }

    public MediaType getMediaType() {
        return mediaType;
    }

    public String getMediaTypeString() {
        return mediaTypeString;
    }

    /**
     * 根据扩展名或格式名称解析对应格式枚举
     *
     * @param format 格式字符串 (如 "json", "yaml", "yml")
     * @return 对应的 OpenApiFormat，未匹配默认返回 JSON
     */
    public static OpenApiFormat from(String format) {
        if (format == null || format.isBlank()) {
            return JSON;
        }
        String clean = format.trim().toLowerCase();
        if ("yaml".equals(clean) || "yml".equals(clean)) {
            return YAML;
        }
        return JSON;
    }
}
