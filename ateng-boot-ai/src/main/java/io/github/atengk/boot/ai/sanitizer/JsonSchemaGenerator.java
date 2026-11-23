package io.github.atengk.boot.ai.sanitizer;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.atengk.boot.core.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 纯原生强类型 JSON Schema 生成器
 * <p>
 * 依托 JDK 17 原生反射与 Jackson 树模型，将 Java POJO 契约模型动态转换为符合 OpenAPI / JSON Schema 规范的语法约束描述，
 * 专为大语言模型原生结构化解码 (JSON_SCHEMA & STRICT) 提供 tokenizer 级别的语法生成防卫，零臃肿第三方依赖。
 *
 * @author Ateng
 * @since 2026-11-05
 */
public final class JsonSchemaGenerator {

    private static final Logger log = LoggerFactory.getLogger(JsonSchemaGenerator.class);

    private JsonSchemaGenerator() {
    }

    /**
     * 根据目标 Class 动态生成符合规范的标准 JSON Schema 字符串
     *
     * @param targetClass 目标反序列化 POJO 类型
     * @return 紧凑纯净的 JSON Schema 字符串
     */
    public static String generateSchema(Class<?> targetClass) {
        return generateSchema(targetClass, false);
    }

    /**
     * 生成兼容 OpenAI 原生 Structured Outputs（strict=true）的 JSON Schema 字符串，
     * 递归为所有 object 节点附加 {@code additionalProperties: false}
     *
     * @param targetClass 目标反序列化 Class 类型
     * @return 严格模式的 JSON Schema 字符串
     */
    public static String generateStrictSchema(Class<?> targetClass) {
        return generateSchema(targetClass, true);
    }

    private static String generateSchema(Class<?> targetClass, boolean strict) {
        if (targetClass == null) {
            return strict ? "{\"type\":\"object\",\"additionalProperties\":false}" : "{\"type\":\"object\"}";
        }
        try {
            ObjectNode schemaNode = generateObjectSchema(targetClass, new HashSet<>(), strict);
            return schemaNode.toString();
        } catch (Exception e) {
            log.warn("[AI-JSON-SCHEMA] 动态生成 JSON Schema 失败 [class={}]: {}", targetClass.getName(), e.getMessage(), e);
            return strict ? "{\"type\":\"object\",\"additionalProperties\":false}" : "{\"type\":\"object\"}";
        }
    }

    private static ObjectNode generateObjectSchema(Class<?> clazz, Set<Class<?>> visited) {
        return generateObjectSchema(clazz, visited, false);
    }

    private static ObjectNode generateObjectSchema(Class<?> clazz, Set<Class<?>> visited, boolean strict) {
        ObjectNode objectNode = (ObjectNode) JsonUtil.readTree("{}");
        objectNode.put("type", "object");
        if (strict) {
            objectNode.put("additionalProperties", false);
        }

        if (clazz == null || Object.class.equals(clazz) || visited.contains(clazz)) {
            return objectNode;
        }
        visited.add(clazz);

        ObjectNode propertiesNode = objectNode.putObject("properties");
        ArrayNode requiredNode = objectNode.putArray("required");

        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                continue;
            }

            String fieldName = field.getName();
            ObjectNode propertySchema = resolveTypeSchema(field.getType(), field.getGenericType(), visited);
            if (strict) {
                applyStrictAdditionalProperties(propertySchema);
            }
            propertiesNode.set(fieldName, propertySchema);
            requiredNode.add(fieldName);
        }

        visited.remove(clazz);
        return objectNode;
    }

    private static void applyStrictAdditionalProperties(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isObject()) {
            return;
        }
        ObjectNode objectNode = (ObjectNode) node;
        if ("object".equals(objectNode.path("type").asText())) {
            objectNode.put("additionalProperties", false);
            com.fasterxml.jackson.databind.JsonNode props = objectNode.get("properties");
            if (props != null && props.isObject()) {
                props.fields().forEachRemaining(entry -> applyStrictAdditionalProperties(entry.getValue()));
            }
        }
        com.fasterxml.jackson.databind.JsonNode items = objectNode.get("items");
        if (items != null) {
            applyStrictAdditionalProperties(items);
        }
    }

    private static ObjectNode resolveTypeSchema(Class<?> type, Type genericType, Set<Class<?>> visited) {
        ObjectNode node = (ObjectNode) JsonUtil.readTree("{}");

        if (String.class.equals(type) || Character.class.equals(type) || char.class.equals(type)) {
            node.put("type", "string");
            return node;
        }

        if (type.isEnum()) {
            node.put("type", "string");
            ArrayNode enumArray = node.putArray("enum");
            for (Object enumConstant : type.getEnumConstants()) {
                enumArray.add(enumConstant.toString());
            }
            return node;
        }

        if (Integer.class.equals(type) || int.class.equals(type)
                || Long.class.equals(type) || long.class.equals(type)
                || Short.class.equals(type) || short.class.equals(type)
                || Byte.class.equals(type) || byte.class.equals(type)) {
            node.put("type", "integer");
            return node;
        }

        if (Double.class.equals(type) || double.class.equals(type)
                || Float.class.equals(type) || float.class.equals(type)
                || BigDecimal.class.equals(type) || Number.class.isAssignableFrom(type)) {
            node.put("type", "number");
            return node;
        }

        if (Boolean.class.equals(type) || boolean.class.equals(type)) {
            node.put("type", "boolean");
            return node;
        }

        if (Collection.class.isAssignableFrom(type) || type.isArray()) {
            node.put("type", "array");
            Class<?> itemType = Object.class;
            if (type.isArray()) {
                itemType = type.getComponentType();
            } else if (genericType instanceof ParameterizedType pt) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length > 0 && args[0] instanceof Class<?> itemClass) {
                    itemType = itemClass;
                }
            }
            node.set("items", resolveTypeSchema(itemType, null, visited));
            return node;
        }

        // 嵌套复杂对象
        return generateObjectSchema(type, visited);
    }
}
