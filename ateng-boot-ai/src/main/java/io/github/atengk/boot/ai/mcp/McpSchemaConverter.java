package io.github.atengk.boot.ai.mcp;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.List;
import java.util.Map;

/**
 * MCP JSON Schema 到 LangChain4j JsonObjectSchema 强类型转换器
 *
 * @author Ateng
 * @since 2026-11-05
 */
public final class McpSchemaConverter {

    private McpSchemaConverter() {
    }

    /**
     * 将 MCP 工具描述的 inputSchema Map 转换为 LangChain4j 的 JsonObjectSchema
     *
     * @param inputSchema MCP 输入模式 Map
     * @return JsonObjectSchema 实例
     */
    @SuppressWarnings("unchecked")
    public static JsonObjectSchema convert(Map<String, Object> inputSchema) {
        if (inputSchema == null || inputSchema.isEmpty()) {
            return JsonObjectSchema.builder().build();
        }

        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();

        Object descObj = inputSchema.get("description");
        if (descObj instanceof String desc) {
            builder.description(desc);
        }

        Object propsObj = inputSchema.get("properties");
        if (propsObj instanceof Map<?, ?> propsMap) {
            for (Map.Entry<?, ?> entry : propsMap.entrySet()) {
                String propName = String.valueOf(entry.getKey());
                if (entry.getValue() instanceof Map<?, ?> propDef) {
                    builder.addProperty(propName, convertElement((Map<String, Object>) propDef));
                } else {
                    builder.addStringProperty(propName);
                }
            }
        }

        Object requiredObj = inputSchema.get("required");
        if (requiredObj instanceof List<?> reqList) {
            List<String> required = reqList.stream().map(String::valueOf).toList();
            builder.required(required);
        }

        return builder.build();
    }

    private static JsonSchemaElement convertElement(Map<String, Object> propDef) {
        String type = propDef.get("type") != null ? String.valueOf(propDef.get("type")).toLowerCase() : "string";
        String description = propDef.get("description") != null ? String.valueOf(propDef.get("description")) : null;

        return switch (type) {
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            case "array" -> JsonArraySchema.builder().description(description).build();
            default -> JsonStringSchema.builder().description(description).build();
        };
    }
}
