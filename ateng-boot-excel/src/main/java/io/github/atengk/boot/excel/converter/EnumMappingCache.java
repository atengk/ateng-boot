package io.github.atengk.boot.excel.converter;

import io.github.atengk.boot.excel.annotation.ExcelEnum;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 枚举双向映射元数据与解析缓存
 * <p>
 * 提供高性能反射解析与双向查找字典，确保导出与导入阶段 $O(1)$ 高速命中。
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class EnumMappingCache {

    private static final Map<CacheKey, EnumMeta> CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Set<Class<?>>> ENTITY_ENUMS_CACHE = new ConcurrentHashMap<>();

    private EnumMappingCache() {
    }

    /**
     * 获取或解析枚举元数据
     *
     * @param enumClass 枚举 Class
     * @param excelEnum 注解元数据（可为 null）
     * @return 枚举双向映射元数据
     */
    public static EnumMeta getEnumMeta(Class<?> enumClass, ExcelEnum excelEnum) {
        if (enumClass == null || !enumClass.isEnum()) {
            return null;
        }
        if (excelEnum == null) {
            excelEnum = enumClass.getAnnotation(ExcelEnum.class);
        }
        String codeField = (excelEnum != null && !excelEnum.codeField().isEmpty()) ? excelEnum.codeField() : "code";
        String textField = (excelEnum != null && !excelEnum.textField().isEmpty()) ? excelEnum.textField() : "text";
        boolean ignoreUnmapped = (excelEnum == null || excelEnum.ignoreUnmapped());

        CacheKey key = new CacheKey(enumClass, codeField, textField, ignoreUnmapped);
        return CACHE.computeIfAbsent(key, k -> parseEnumMeta(enumClass, codeField, textField, ignoreUnmapped));
    }

    /**
     * 解析字段对应的枚举 Class
     *
     * @param field 目标字段
     * @return 对应的枚举 Class；非枚举字段且未显式指定枚举类时返回 null
     */
    public static Class<?> resolveEnumClass(Field field) {
        if (field == null) {
            return null;
        }
        ExcelEnum excelEnum = field.getAnnotation(ExcelEnum.class);
        if (excelEnum != null && excelEnum.value() != ExcelEnum.VoidEnum.class) {
            return excelEnum.value();
        }
        if (field.getType().isEnum()) {
            return field.getType();
        }
        return null;
    }

    /**
     * 获取字段上的 @ExcelEnum 注解
     *
     * @param field 目标字段
     * @return 注解实例（若不存在返回 null）
     */
    public static ExcelEnum resolveEnumAnnotation(Field field) {
        return (field != null) ? field.getAnnotation(ExcelEnum.class) : null;
    }

    /**
     * 扫描实体类继承树中包含的全部枚举 Class（带并发缓存保护）
     *
     * @param entityClass 实体类 Class
     * @return 实体类引用的枚举类型集合
     */
    public static Set<Class<?>> getEntityEnumClasses(Class<?> entityClass) {
        if (entityClass == null || entityClass == Object.class) {
            return Collections.emptySet();
        }
        return ENTITY_ENUMS_CACHE.computeIfAbsent(entityClass, EnumMappingCache::scanEntityEnumClasses);
    }

    private static Set<Class<?>> scanEntityEnumClasses(Class<?> entityClass) {
        Set<Class<?>> result = new HashSet<>();
        Class<?> current = entityClass;
        while (current != null && current != Object.class) {
            Field[] declaredFields = current.getDeclaredFields();
            for (Field field : declaredFields) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                Class<?> enumClass = resolveEnumClass(field);
                if (enumClass != null && enumClass.isEnum()) {
                    result.add(enumClass);
                }
            }
            current = current.getSuperclass();
        }
        return Collections.unmodifiableSet(result);
    }

    @SuppressWarnings("unchecked")
    private static EnumMeta parseEnumMeta(Class<?> enumClass, String codeField, String textField, boolean ignoreUnmapped) {
        Enum<?>[] constants = ((Class<Enum<?>>) enumClass).getEnumConstants();
        if (constants == null || constants.length == 0) {
            return new EnumMeta(enumClass, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), ignoreUnmapped);
        }

        Map<Object, String> valueToTextMap = new HashMap<>(constants.length * 4);
        Map<String, Enum<?>> textToEnumMap = new HashMap<>(constants.length * 4);
        Map<String, Object> textToCodeMap = new HashMap<>(constants.length * 4);

        for (Enum<?> constant : constants) {
            Object codeVal = extractValue(constant, codeField, true);
            String textVal = extractText(constant, textField);

            // 1. 实例与原始 code 均能映射到 text
            valueToTextMap.put(constant, textVal);
            valueToTextMap.put(constant.name(), textVal);
            if (codeVal != null) {
                valueToTextMap.put(codeVal, textVal);
                valueToTextMap.put(String.valueOf(codeVal), textVal);
            }

            // 2. 中文 text、code 字符串、name 均能反向映射回枚举实例
            textToEnumMap.put(textVal, constant);
            textToEnumMap.put(constant.name(), constant);
            if (codeVal != null) {
                textToEnumMap.put(String.valueOf(codeVal), constant);
                textToCodeMap.put(textVal, codeVal);
                textToCodeMap.put(constant.name(), codeVal);
                textToCodeMap.put(String.valueOf(codeVal), codeVal);
            } else {
                textToCodeMap.put(textVal, constant.name());
            }
        }

        return new EnumMeta(enumClass,
                Collections.unmodifiableMap(valueToTextMap),
                Collections.unmodifiableMap(textToEnumMap),
                Collections.unmodifiableMap(textToCodeMap),
                ignoreUnmapped);
    }

    private static Object extractValue(Enum<?> constant, String customField, boolean fallbackToName) {
        // 1. 尝试自定义属性与对应 getter
        Object val = invokeGetterOrField(constant, customField);
        if (val != null) {
            return val;
        }
        // 2. 尝试 getValue()
        val = invokeGetterOrField(constant, "value");
        if (val != null) {
            return val;
        }
        // 3. 回退
        return fallbackToName ? constant.name() : null;
    }

    private static String extractText(Enum<?> constant, String customField) {
        // 1. 尝试自定义文本属性
        Object val = invokeGetterOrField(constant, customField);
        if (val != null) {
            return String.valueOf(val);
        }
        // 2. 常见候选尝试: desc, label, message, text
        for (String candidate : new String[]{"desc", "label", "message", "text"}) {
            val = invokeGetterOrField(constant, candidate);
            if (val != null) {
                return String.valueOf(val);
            }
        }
        // 3. 最终回退至常量名
        return constant.name();
    }

    private static Object invokeGetterOrField(Enum<?> constant, String propertyName) {
        if (propertyName == null || propertyName.isEmpty()) {
            return null;
        }
        Class<?> clazz = constant.getClass();
        // 优先尝试 getter (如 getCode() 或 code())
        String getterName = "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        try {
            Method method = clazz.getMethod(getterName);
            return method.invoke(constant);
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Method method = clazz.getMethod(propertyName);
            return method.invoke(constant);
        } catch (ReflectiveOperationException ignored) {
        }
        // 尝试直接读取属性
        try {
            Field field = clazz.getDeclaredField(propertyName);
            field.setAccessible(true);
            return field.get(constant);
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    /**
     * 枚举双向元数据包装对象
     */
    public record EnumMeta(
            Class<?> enumClass,
            Map<Object, String> valueToTextMap,
            Map<String, Enum<?>> textToEnumMap,
            Map<String, Object> textToCodeMap,
            boolean ignoreUnmapped
    ) {
        public String toText(Object value) {
            if (value == null) {
                return null;
            }
            String text = valueToTextMap.get(value);
            if (text != null) {
                return text;
            }
            text = valueToTextMap.get(String.valueOf(value));
            if (text != null) {
                return text;
            }
            return ignoreUnmapped ? String.valueOf(value) : null;
        }

        public Enum<?> toEnum(String text) {
            if (text == null) {
                return null;
            }
            Enum<?> match = textToEnumMap.get(text);
            if (match != null) {
                return match;
            }
            return textToEnumMap.get(text.trim());
        }

        public Object toCode(String text) {
            if (text == null) {
                return null;
            }
            Object code = textToCodeMap.get(text);
            if (code != null) {
                return code;
            }
            return textToCodeMap.get(text.trim());
        }
    }

    private record CacheKey(Class<?> enumClass, String codeField, String textField, boolean ignoreUnmapped) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CacheKey cacheKey = (CacheKey) o;
            return ignoreUnmapped == cacheKey.ignoreUnmapped
                    && Objects.equals(enumClass, cacheKey.enumClass)
                    && Objects.equals(codeField, cacheKey.codeField)
                    && Objects.equals(textField, cacheKey.textField);
        }

        @Override
        public int hashCode() {
            return Objects.hash(enumClass, codeField, textField, ignoreUnmapped);
        }
    }
}
