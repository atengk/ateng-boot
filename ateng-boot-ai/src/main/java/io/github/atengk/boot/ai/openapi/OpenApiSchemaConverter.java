package io.github.atengk.boot.ai.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * OpenAPI 3.0 与 Swagger 2.0 契约文档转 LangChain4j 工具规格与模式转换器
 *
 * @author Ateng
 * @since 2026-11-06
 */
public final class OpenApiSchemaConverter {

    private static final Logger log = LoggerFactory.getLogger(OpenApiSchemaConverter.class);

    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "post", "put", "delete", "patch", "head", "options", "trace"
    );

    private OpenApiSchemaConverter() {
    }

    /**
     * 从契约 JSON 根节点中解析全部 HTTP 操作元数据
     *
     * @param rootNode OpenAPI / Swagger 根 JSON 节点
     * @return 操作元数据列表
     */
    public static List<OpenApiOperationMeta> parseOperations(JsonNode rootNode) {
        if (rootNode == null || !rootNode.has("paths")) {
            return Collections.emptyList();
        }

        List<OpenApiOperationMeta> operations = new ArrayList<>();
        JsonNode pathsNode = rootNode.get("paths");

        Iterator<Map.Entry<String, JsonNode>> fields = pathsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = fields.next();
            String path = pathEntry.getKey();
            JsonNode pathItemNode = pathEntry.getValue();

            // 解析 path 层级的通用参数（若有）
            List<OpenApiOperationMeta.ParameterMeta> pathLevelParams = parseParameters(pathItemNode.get("parameters"), rootNode);

            Iterator<Map.Entry<String, JsonNode>> opFields = pathItemNode.fields();
            while (opFields.hasNext()) {
                Map.Entry<String, JsonNode> opEntry = opFields.next();
                String methodKey = opEntry.getKey().toLowerCase(Locale.ROOT);
                if (!HTTP_METHODS.contains(methodKey)) {
                    continue;
                }

                JsonNode opNode = opEntry.getValue();
                String operationId = resolveOperationId(opNode, methodKey, path);
                String summary = opNode.has("summary") ? opNode.get("summary").asText() : "";
                String description = opNode.has("description") ? opNode.get("description").asText() : "";
                boolean deprecated = opNode.has("deprecated") && opNode.get("deprecated").asBoolean();

                // 解析操作层级参数并与路径级参数合并
                List<OpenApiOperationMeta.ParameterMeta> opParams = new ArrayList<>(pathLevelParams);
                if (opNode.has("parameters")) {
                    List<OpenApiOperationMeta.ParameterMeta> specificParams = parseParameters(opNode.get("parameters"), rootNode);
                    for (OpenApiOperationMeta.ParameterMeta param : specificParams) {
                        opParams.removeIf(p -> p.name().equals(param.name()) && p.in().equalsIgnoreCase(param.in()));
                        opParams.add(param);
                    }
                }

                // 解析 RequestBody (OpenAPI 3.0 或 Swagger 2.0 body 参数)
                JsonNode requestBodySchema = null;
                boolean requestBodyRequired = false;

                if (opNode.has("requestBody")) {
                    // OpenAPI 3.0
                    JsonNode rbNode = resolveRef(rootNode, opNode.get("requestBody"), new HashSet<>());
                    requestBodyRequired = rbNode.has("required") && rbNode.get("required").asBoolean();
                    if (rbNode.has("content")) {
                        JsonNode contentNode = rbNode.get("content");
                        if (contentNode.has("application/json") && contentNode.get("application/json").has("schema")) {
                            requestBodySchema = contentNode.get("application/json").get("schema");
                        } else {
                            // 取首个 content 媒体类型
                            Iterator<JsonNode> mediaTypes = contentNode.elements();
                            if (mediaTypes.hasNext()) {
                                JsonNode mtNode = mediaTypes.next();
                                if (mtNode.has("schema")) {
                                    requestBodySchema = mtNode.get("schema");
                                }
                            }
                        }
                    }
                } else {
                    // Swagger 2.0 in: body 参数
                    for (OpenApiOperationMeta.ParameterMeta param : opParams) {
                        if ("body".equalsIgnoreCase(param.in())) {
                            requestBodySchema = param.schema();
                            requestBodyRequired = param.required();
                            break;
                        }
                    }
                    // 移除已被提取为 requestBody 的 body 参数
                    opParams.removeIf(p -> "body".equalsIgnoreCase(p.in()));
                }

                operations.add(new OpenApiOperationMeta(
                        operationId,
                        path,
                        methodKey.toUpperCase(Locale.ROOT),
                        summary,
                        description,
                        deprecated,
                        opParams,
                        requestBodySchema,
                        requestBodyRequired
                ));
            }
        }

        return operations;
    }

    /**
     * 从契约 JSON 中探测基地址 baseUrl
     *
     * @param rootNode OpenAPI / Swagger 根 JSON 节点
     * @return 探测到的 baseUrl，探测失败返回 null
     */
    public static String detectBaseUrl(JsonNode rootNode) {
        if (rootNode == null) {
            return null;
        }

        // 1. OpenAPI 3.0: servers[0].url
        if (rootNode.has("servers") && rootNode.get("servers").isArray()) {
            ArrayNode servers = (ArrayNode) rootNode.get("servers");
            if (!servers.isEmpty()) {
                JsonNode firstServer = servers.get(0);
                if (firstServer.has("url")) {
                    String url = firstServer.get("url").asText();
                    if (StringUtils.hasText(url) && !url.startsWith("/")) {
                        return cleanTrailingSlash(url);
                    }
                }
            }
        }

        // 2. Swagger 2.0: schemes[0] + "://" + host + basePath
        if (rootNode.has("host")) {
            String host = rootNode.get("host").asText();
            String scheme = "http";
            if (rootNode.has("schemes") && rootNode.get("schemes").isArray() && !rootNode.get("schemes").isEmpty()) {
                scheme = rootNode.get("schemes").get(0).asText("http");
            }
            String basePath = rootNode.has("basePath") ? rootNode.get("basePath").asText("") : "";
            if (!basePath.startsWith("/")) {
                basePath = "/" + basePath;
            }
            if ("/".equals(basePath)) {
                basePath = "";
            }
            return cleanTrailingSlash(scheme + "://" + host + basePath);
        }

        return null;
    }

    /**
     * 将单个 OpenAPI 操作转换为 LangChain4j 的 ToolSpecification
     *
     * @param op       操作元数据
     * @param rootNode 契约根节点（用于解析 $ref）
     * @return ToolSpecification 实例
     */
    public static ToolSpecification toToolSpecification(OpenApiOperationMeta op, JsonNode rootNode) {
        JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();
        List<String> requiredList = new ArrayList<>();

        // 1. 添加 Path, Query, Header 参数到 Schema
        for (OpenApiOperationMeta.ParameterMeta param : op.getParameters()) {
            String paramName = param.name();
            String paramDesc = StringUtils.hasText(param.description()) ? param.description() : param.name();
            if (StringUtils.hasText(param.in())) {
                paramDesc = "[" + param.in() + "] " + paramDesc;
            }

            JsonSchemaElement element = convertParameterSchema(param, rootNode);
            schemaBuilder.addProperty(paramName, element);

            if (param.required()) {
                requiredList.add(paramName);
            }
        }

        // 2. 扁平化注入或挂载 RequestBody Schema
        if (op.getRequestBodySchema() != null) {
            JsonNode rbSchema = resolveRef(rootNode, op.getRequestBodySchema(), new HashSet<>());
            if (rbSchema != null) {
                // 如果是 Object 类型且包含 properties，执行双模支持：
                // ① 将 body 的内部字段展平注入顶层，提升大模型调用直觉
                // ② 注册 body / requestBody 属性作为独立对象，兼容结构化传参
                if (rbSchema.has("properties")) {
                    JsonNode props = rbSchema.get("properties");
                    Iterator<Map.Entry<String, JsonNode>> propEntries = props.fields();
                    while (propEntries.hasNext()) {
                        Map.Entry<String, JsonNode> pEntry = propEntries.next();
                        String fieldName = pEntry.getKey();
                        // 避免与已有 path/query 参数同名冲突
                        if (!hasParameterNamed(op.getParameters(), fieldName)) {
                            JsonSchemaElement fieldElement = convertElement(pEntry.getValue(), rootNode, new HashSet<>());
                            schemaBuilder.addProperty(fieldName, fieldElement);
                        }
                    }

                    if (rbSchema.has("required") && rbSchema.get("required").isArray()) {
                        for (JsonNode reqNode : rbSchema.get("required")) {
                            String reqField = reqNode.asText();
                            if (!requiredList.contains(reqField) && !hasParameterNamed(op.getParameters(), reqField)) {
                                requiredList.add(reqField);
                            }
                        }
                    }
                }

                // 同时将完整的 body 作为可选对象模式挂载
                JsonSchemaElement bodyElement = convertElement(rbSchema, rootNode, new HashSet<>());
                schemaBuilder.addProperty("body", bodyElement);
                if (op.isRequestBodyRequired() && requiredList.isEmpty()) {
                    requiredList.add("body");
                }
            }
        }

        if (!requiredList.isEmpty()) {
            schemaBuilder.required(requiredList);
        }

        String toolDesc = StringUtils.hasText(op.getDescription()) ? op.getDescription() : op.getSummary();
        if (!StringUtils.hasText(toolDesc)) {
            toolDesc = op.getHttpMethod() + " " + op.getPath();
        }

        return ToolSpecification.builder()
                .name(op.getOperationId())
                .description(toolDesc)
                .parameters(schemaBuilder.build())
                .build();
    }

    /**
     * 将单个参数元数据转换为 JsonSchemaElement
     */
    private static JsonSchemaElement convertParameterSchema(OpenApiOperationMeta.ParameterMeta param, JsonNode rootNode) {
        String desc = StringUtils.hasText(param.description()) ? param.description() : param.name();
        if (param.schema() != null) {
            return convertElement(param.schema(), rootNode, new HashSet<>());
        }

        String type = param.type() != null ? param.type().toLowerCase(Locale.ROOT) : "string";
        return switch (type) {
            case "integer", "int" -> JsonIntegerSchema.builder().description(desc).build();
            case "number", "float", "double" -> JsonNumberSchema.builder().description(desc).build();
            case "boolean" -> JsonBooleanSchema.builder().description(desc).build();
            case "array" -> JsonArraySchema.builder().description(desc).build();
            default -> JsonStringSchema.builder().description(desc).build();
        };
    }

    /**
     * 将任意 JsonNode Schema 递归转换为 LangChain4j 的 JsonSchemaElement
     *
     * @param schemaNode  模式节点
     * @param rootNode    根节点
     * @param visitedRefs 循环引用防卫集合
     * @return 转换后的 JsonSchemaElement
     */
    public static JsonSchemaElement convertElement(JsonNode schemaNode, JsonNode rootNode, Set<String> visitedRefs) {
        if (schemaNode == null) {
            return JsonStringSchema.builder().build();
        }

        // 解析 $ref 引用
        schemaNode = resolveRef(rootNode, schemaNode, visitedRefs);
        if (schemaNode == null) {
            return JsonStringSchema.builder().description("Unresolved reference").build();
        }

        String description = schemaNode.has("description") ? schemaNode.get("description").asText() : null;
        String type = schemaNode.has("type") ? schemaNode.get("type").asText().toLowerCase(Locale.ROOT) : "object";

        // 枚举检测
        if (schemaNode.has("enum") && schemaNode.get("enum").isArray()) {
            List<String> enumValues = new ArrayList<>();
            for (JsonNode ev : schemaNode.get("enum")) {
                enumValues.add(ev.asText());
            }
            return JsonEnumSchema.builder()
                    .description(description)
                    .enumValues(enumValues)
                    .build();
        }

        return switch (type) {
            case "integer", "int" -> JsonIntegerSchema.builder().description(description).build();
            case "number", "float", "double" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            case "array" -> {
                JsonArraySchema.Builder arrBuilder = JsonArraySchema.builder().description(description);
                if (schemaNode.has("items")) {
                    JsonSchemaElement itemElement = convertElement(schemaNode.get("items"), rootNode, visitedRefs);
                    arrBuilder.items(itemElement);
                }
                yield arrBuilder.build();
            }
            case "object" -> {
                JsonObjectSchema.Builder objBuilder = JsonObjectSchema.builder().description(description);
                if (schemaNode.has("properties")) {
                    JsonNode props = schemaNode.get("properties");
                    Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        objBuilder.addProperty(field.getKey(), convertElement(field.getValue(), rootNode, visitedRefs));
                    }
                }
                if (schemaNode.has("required") && schemaNode.get("required").isArray()) {
                    List<String> requiredList = new ArrayList<>();
                    for (JsonNode req : schemaNode.get("required")) {
                        requiredList.add(req.asText());
                    }
                    objBuilder.required(requiredList);
                }
                yield objBuilder.build();
            }
            default -> JsonStringSchema.builder().description(description).build();
        };
    }

    /**
     * 沿着 $ref 路径寻址并解析 JSON 节点（支持 #/components/schemas/* 与 #/definitions/*）
     *
     * @param rootNode 契约根节点
     * @param node     待解析节点
     * @param visited  已访问集合（防死循环）
     * @return 解析后的目标节点
     */
    public static JsonNode resolveRef(JsonNode rootNode, JsonNode node, Set<String> visited) {
        if (node == null || !node.has("$ref") || rootNode == null) {
            return node;
        }

        String ref = node.get("$ref").asText();
        if (!visited.add(ref)) {
            log.warn("[AI-OPENAPI-SCHEMA] 检测到模式定义循环引用: {}", ref);
            return node;
        }

        if (ref.startsWith("#/")) {
            String[] tokens = ref.substring(2).split("/");
            JsonNode current = rootNode;
            for (String token : tokens) {
                // 处理 ~1 替换为 /，~0 替换为 ~ (JSON Pointer 规范)
                String unescaped = token.replace("~1", "/").replace("~0", "~");
                if (current.has(unescaped)) {
                    current = current.get(unescaped);
                } else {
                    log.warn("[AI-OPENAPI-SCHEMA] 无法解析指针路径: {} -> token: {}", ref, unescaped);
                    return node;
                }
            }
            return resolveRef(rootNode, current, visited);
        }

        return node;
    }

    private static List<OpenApiOperationMeta.ParameterMeta> parseParameters(JsonNode paramsNode, JsonNode rootNode) {
        if (paramsNode == null || !paramsNode.isArray()) {
            return Collections.emptyList();
        }

        List<OpenApiOperationMeta.ParameterMeta> list = new ArrayList<>();
        for (JsonNode paramNode : paramsNode) {
            JsonNode resolved = resolveRef(rootNode, paramNode, new HashSet<>());
            if (resolved == null || !resolved.has("name")) {
                continue;
            }

            String name = resolved.get("name").asText();
            String in = resolved.has("in") ? resolved.get("in").asText() : "query";
            boolean required = resolved.has("required") && resolved.get("required").asBoolean();
            String description = resolved.has("description") ? resolved.get("description").asText() : "";
            String type = resolved.has("type") ? resolved.get("type").asText() : null;
            JsonNode schema = resolved.has("schema") ? resolved.get("schema") : null;

            list.add(new OpenApiOperationMeta.ParameterMeta(name, in, required, description, type, schema));
        }

        return list;
    }

    private static String resolveOperationId(JsonNode opNode, String method, String path) {
        if (opNode.has("operationId") && StringUtils.hasText(opNode.get("operationId").asText())) {
            return sanitizeIdentifier(opNode.get("operationId").asText());
        }

        // 根据 method + path 自动生成驼峰 operationId
        // 例如：GET /api/v1/orders/{id} -> getApiV1OrdersById
        StringBuilder sb = new StringBuilder(method.toLowerCase(Locale.ROOT));
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (!StringUtils.hasText(segment)) {
                continue;
            }
            String clean = segment.replaceAll("[^a-zA-Z0-9]", "");
            if (segment.startsWith("{") && segment.endsWith("}")) {
                clean = "By" + capitalize(clean);
            } else {
                clean = capitalize(clean);
            }
            sb.append(clean);
        }
        return sb.toString();
    }

    private static String sanitizeIdentifier(String raw) {
        // 确保符合大模型 Tool 名称命名规范（字母数字下划线破折号）
        return raw.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String capitalize(String str) {
        if (!StringUtils.hasText(str)) {
            return "";
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    public static String cleanTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static boolean hasParameterNamed(List<OpenApiOperationMeta.ParameterMeta> parameters, String name) {
        if (parameters == null || parameters.isEmpty()) {
            return false;
        }
        for (OpenApiOperationMeta.ParameterMeta p : parameters) {
            if (p.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
