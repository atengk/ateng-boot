package io.github.atengk.boot.search.util;

import io.github.atengk.boot.search.annotation.SearchId;
import io.github.atengk.boot.search.annotation.SearchIndex;
import io.github.atengk.boot.search.annotation.SearchRouting;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.ParserContext;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
    private static final Map<Class<?>, Field> ROUTING_FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> HAS_ROUTING_CACHE = new ConcurrentHashMap<>();

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

    /**
     * 判断实体类是否显式标注了 @SearchRouting 注解
     *
     * @param clazz 实体类类型
     * @return true 表示配置了分片路由注解
     */
    public static boolean hasRoutingAnnotation(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        return HAS_ROUTING_CACHE.computeIfAbsent(clazz, k -> findRoutingField(k) != null);
    }

    /**
     * 解析实体对象的分片自定义路由键 (Routing Key)
     * 自动提取标注了 @SearchRouting 的字段值
     *
     * @param doc 实体对象
     * @return 路由键字符串，若无则返回 null
     */
    public static String resolveRouting(Object doc) {
        if (doc == null) {
            return null;
        }
        Field routingField = ROUTING_FIELD_CACHE.computeIfAbsent(doc.getClass(), SearchMetadataHelper::findRoutingField);
        if (routingField != null) {
            try {
                routingField.setAccessible(true);
                Object val = routingField.get(doc);
                return val != null ? String.valueOf(val) : null;
            } catch (Exception ignored) {
                // 静默忽略反射错误
            }
        }
        return null;
    }

    private static Field findRoutingField(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            Field[] fields = current.getDeclaredFields();
            for (Field field : fields) {
                if (field.isAnnotationPresent(SearchRouting.class)) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
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
        if (!StringUtils.hasText(expressionStr)) {
            return expressionStr;
        }
        try {
            StandardEvaluationContext context = new StandardEvaluationContext();
            context.addMethodResolver(new DateFormatMethodResolver());
            Expression expression = SPEL_PARSER.parseExpression(expressionStr, ParserContext.TEMPLATE_EXPRESSION);
            Object evaluated = expression.getValue(context);
            return evaluated != null ? evaluated.toString() : expressionStr;
        } catch (Exception ignored) {
            return expressionStr;
        }
    }

    private static class DateFormatMethodResolver implements MethodResolver {
        @Override
        public MethodExecutor resolve(
                EvaluationContext context,
                Object targetObject,
                String name,
                List<TypeDescriptor> argumentTypes) {
            if ("format".equals(name) && argumentTypes != null && argumentTypes.size() == 1
                    && String.class.isAssignableFrom(argumentTypes.get(0).getType())) {
                if (targetObject instanceof LocalDate) {
                    return (ctx, target, args) -> new TypedValue(((LocalDate) target)
                            .format(DateTimeFormatter.ofPattern((String) args[0])));
                }
                if (targetObject instanceof LocalDateTime) {
                    return (ctx, target, args) -> new TypedValue(((LocalDateTime) target)
                            .format(DateTimeFormatter.ofPattern((String) args[0])));
                }
            }
            return null;
        }
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
