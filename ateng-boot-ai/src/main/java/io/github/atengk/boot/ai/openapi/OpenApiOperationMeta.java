package io.github.atengk.boot.ai.openapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OpenAPI 单个操作端点解析元数据
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class OpenApiOperationMeta {

    private final String operationId;
    private final String path;
    private final String httpMethod;
    private final String summary;
    private final String description;
    private final boolean deprecated;
    private final List<ParameterMeta> parameters;
    private final JsonNode requestBodySchema;
    private final boolean requestBodyRequired;

    public OpenApiOperationMeta(
            String operationId,
            String path,
            String httpMethod,
            String summary,
            String description,
            boolean deprecated,
            List<ParameterMeta> parameters,
            JsonNode requestBodySchema,
            boolean requestBodyRequired) {
        this.operationId = operationId;
        this.path = path;
        this.httpMethod = httpMethod != null ? httpMethod.toUpperCase() : "GET";
        this.summary = summary;
        this.description = description;
        this.deprecated = deprecated;
        this.parameters = parameters != null ? Collections.unmodifiableList(parameters) : Collections.emptyList();
        this.requestBodySchema = requestBodySchema;
        this.requestBodyRequired = requestBodyRequired;
    }

    public String getOperationId() {
        return operationId;
    }

    public String getPath() {
        return path;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getSummary() {
        return summary;
    }

    public String getDescription() {
        return description;
    }

    public boolean isDeprecated() {
        return deprecated;
    }

    public List<ParameterMeta> getParameters() {
        return parameters;
    }

    public JsonNode getRequestBodySchema() {
        return requestBodySchema;
    }

    public boolean isRequestBodyRequired() {
        return requestBodyRequired;
    }

    /**
     * 获取指定位置的参数元数据列表
     *
     * @param in 参数所在位置（path, query, header）
     * @return 符合位置条件的参数元数据列表
     */
    public List<ParameterMeta> getParametersByIn(String in) {
        if (parameters.isEmpty() || in == null) {
            return Collections.emptyList();
        }
        List<ParameterMeta> filtered = new ArrayList<>();
        for (ParameterMeta param : parameters) {
            if (in.equalsIgnoreCase(param.in())) {
                filtered.add(param);
            }
        }
        return filtered;
    }

    /**
     * 单个 HTTP 请求参数元数据
     *
     * @param name        参数名称
     * @param in          参数位置 (path, query, header, cookie)
     * @param required    是否必填
     * @param description 参数描述
     * @param type        数据类型
     * @param schema      关联的模式节点
     */
    public record ParameterMeta(
            String name,
            String in,
            boolean required,
            String description,
            String type,
            JsonNode schema
    ) {
    }
}
