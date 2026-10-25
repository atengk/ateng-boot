package io.github.atengk.boot.search.util;

import io.github.atengk.boot.search.annotation.SearchId;
import io.github.atengk.boot.search.annotation.SearchIndex;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 搜索引擎元数据与注解反射解析辅助工具
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class SearchMetadataHelper {

    private static final ExpressionParser SPEL_PARSER = new SpelExpressionParser();
    private static final Map<Class<?>, String> INDEX_NAME_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> ID_FIELD_CACHE = new ConcurrentHashMap<>();

    private SearchMetadataHelper() {
    }

    /**
     * 解析实体类型对应的索引名称
     * 支持 @SearchIndex 静态名称、SpEL 表达式或默认类名下划线转换
     *
     * @param clazz 实体类
     * @return 索引名称
     */
    public static String resolveIndexName(Class<?> clazz) {
        if (clazz == null) {
            return "";
        }
        SearchIndex searchIndex = clazz.getAnnotation(SearchIndex.class);
        if (searchIndex != null && StringUtils.hasText(searchIndex.value())) {
            String value = searchIndex.value().trim();
            if (value.contains("#{")) {
                return evaluateSpel(value);
            }
            return value;
        }
        return INDEX_NAME_CACHE.computeIfAbsent(clazz, k -> toSnakeCase(k.getSimpleName()));
    }

    /**
     * 解析实体对象的文档主键 ID
     * 优先匹配 @SearchId，其次匹配 jakarta.persistence.Id，兜底匹配名称为 id 的字段
     *
     * @param doc 实体对象
     * @return 文档 ID 字符串，若无则返回 null
     */
    public static String resolveDocumentId(Object doc) {
        if (doc == null) {
            return null;
        }
        Field idField = ID_FIELD_CACHE.computeIfAbsent(doc.getClass(), SearchMetadataHelper::findIdField);
        if (idField != null) {
            try {
                idField.setAccessible(true);
                Object val = idField.get(doc);
                return val != null ? String.valueOf(val) : null;
            } catch (Exception ignored) {
                // 静默忽略反射错误
            }
        }
        return null;
    }

    private static Field findIdField(Class<?> clazz) {
        Class<?> current = clazz;
        Field fallbackIdField = null;
        while (current != null && current != Object.class) {
            Field[] fields = current.getDeclaredFields();
            for (Field field : fields) {
                if (field.isAnnotationPresent(SearchId.class)) {
                    return field;
                }
                if (isJpaId(field)) {
                    return field;
                }
                if (fallbackIdField == null && "id".equalsIgnoreCase(field.getName())) {
                    fallbackIdField = field;
                }
            }
            current = current.getSuperclass();
        }
        return fallbackIdField;
    }

    private static boolean isJpaId(Field field) {
        for (java.lang.annotation.Annotation annotation : field.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if ("jakarta.persistence.Id".equals(name) || "javax.persistence.Id".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String evaluateSpel(String expressionStr) {
        try {
            int start = expressionStr.indexOf("#{");
            int end = expressionStr.indexOf('}', start);
            if (start >= 0 && end > start) {
                String prefix = expressionStr.substring(0, start);
                String expr = expressionStr.substring(start + 2, end);
                String suffix = expressionStr.substring(end + 1);
                Expression expression = SPEL_PARSER.parseExpression(expr);
                Object evaluated = expression.getValue(new StandardEvaluationContext());
                return prefix + (evaluated != null ? evaluated.toString() : "") + suffix;
            }
        } catch (Exception ignored) {
        }
        return expressionStr;
    }

    private static String toSnakeCase(String name) {
        if (!StringUtils.hasText(name)) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && Character.isLowerCase(name.charAt(i - 1))) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
