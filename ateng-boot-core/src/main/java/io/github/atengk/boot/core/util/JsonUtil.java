package io.github.atengk.boot.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import io.github.atengk.boot.core.constant.DatePattern;
import io.github.atengk.boot.core.jackson.SensitiveJsonSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;

/**
 * 纯原生高性能安全 JSON 序列化与反序列化静态门面
 * 统一基于 Jackson 封装，内置东八区时区、JSR-310 日期时间模块与未知属性容错，彻底杜绝 @class 漏洞
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class JsonUtil {

    private static volatile ObjectMapper defaultMapper = createDefaultObjectMapper();
    private static volatile ObjectMapper longToStringMapper = createLongToStringObjectMapper();
    private static volatile ObjectMapper cloneMapper = createCloneObjectMapper();

    private JsonUtil() {
    }

    /**
     * 允许外部框架（如 Spring 容器启动后）动态刷新全局 ObjectMapper 委托实例
     *
     * @param customObjectMapper 定制的 ObjectMapper，若为 null 则重置为默认实例
     */
    public static void init(ObjectMapper customObjectMapper) {
        defaultMapper = customObjectMapper != null ? customObjectMapper : createDefaultObjectMapper();
    }

    /**
     * 获取当前生效的全局默认 ObjectMapper 实例
     *
     * @return 当前 ObjectMapper
     */
    public static ObjectMapper getObjectMapper() {
        return defaultMapper;
    }

    /**
     * 构建一个开箱即用且经过安全加固的默认 ObjectMapper 实例
     * 默认保留 Long 原生数值类型，东八区，JSR-310 标准时间格式
     *
     * @return 安全的默认 ObjectMapper 实例
     */
    public static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        configureBaseFeatures(mapper);
        registerJavaTimeModule(mapper, false);
        return mapper;
    }

    /**
     * 构建一个专供 Web 视图层防前端 53 位精度丢失的 ObjectMapper 实例
     * 将 Long、long 与 BigInteger 序列化为 String
     *
     * @return 带长整型转字符串能力的 ObjectMapper 实例
     */
    public static ObjectMapper createLongToStringObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        configureBaseFeatures(mapper);
        registerJavaTimeModule(mapper, true);
        return mapper;
    }

    /**
     * 构建一个专供纯内存深度克隆的 ObjectMapper 实例
     * 自动过滤出界视图脱敏注解（@FieldSensitive），恪守 ADR-0012 内存真明文原则
     *
     * @return 内存深拷贝专用的 ObjectMapper 实例
     */
    public static ObjectMapper createCloneObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        configureBaseFeatures(mapper);
        registerJavaTimeModule(mapper, false);
        mapper.setAnnotationIntrospector(new JacksonAnnotationIntrospector() {
            @Override
            public Object findSerializer(Annotated a) {
                Object serializer = super.findSerializer(a);
                if (SensitiveJsonSerializer.class.equals(serializer)) {
                    return null;
                }
                return serializer;
            }
        });
        return mapper;
    }

    private static void configureBaseFeatures(ObjectMapper mapper) {
        // 1. 忽略未知属性，防反序列化时新增字段报错
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 2. 统一东八区时区
        mapper.setTimeZone(TimeZone.getTimeZone(DatePattern.DEFAULT_TIME_ZONE));
        // 3. 严禁调用 activateDefaultTyping，从根源杜绝 @class 反序列化漏洞
    }

    private static void registerJavaTimeModule(ObjectMapper mapper, boolean longToString) {
        JavaTimeModule javaTimeModule = new JavaTimeModule();

        // 时间格式化与反序列化
        javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DatePattern.NORM_DATETIME_FORMATTER));
        javaTimeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DatePattern.NORM_DATETIME_FORMATTER));
        javaTimeModule.addSerializer(LocalDate.class, new LocalDateSerializer(DatePattern.NORM_DATE_FORMATTER));
        javaTimeModule.addDeserializer(LocalDate.class, new LocalDateDeserializer(DatePattern.NORM_DATE_FORMATTER));
        javaTimeModule.addSerializer(LocalTime.class, new LocalTimeSerializer(DatePattern.NORM_TIME_FORMATTER));
        javaTimeModule.addDeserializer(LocalTime.class, new LocalTimeDeserializer(DatePattern.NORM_TIME_FORMATTER));

        if (longToString) {
            javaTimeModule.addSerializer(Long.class, ToStringSerializer.instance);
            javaTimeModule.addSerializer(Long.TYPE, ToStringSerializer.instance);
            javaTimeModule.addSerializer(BigInteger.class, ToStringSerializer.instance);
        }

        mapper.registerModule(javaTimeModule);
    }

    // ==================== 序列化 (Object -> JSON) ====================

    /**
     * 将对象序列化为标准 JSON 字符串（默认保留 Long 原生数字类型）
     *
     * @param obj 待序列化对象
     * @return JSON 字符串；入参为 null 时安全返回 null
     */
    public static String toJsonString(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return defaultMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将对象格式化为带有优雅缩进的美化 JSON 字符串
     *
     * @param obj 待序列化对象
     * @return 美化后的 JSON 字符串；入参为 null 时安全返回 null
     */
    public static String toPrettyJsonString(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return defaultMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 美化序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将对象序列化为 JSON 字符串，并将 Long/BigInteger 格式化为 String（专供 Web 视图防失真）
     *
     * @param obj 待序列化对象
     * @return JSON 字符串；入参为 null 时安全返回 null
     */
    public static String toJsonStringWithLongToString(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return longToStringMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将对象序列化为 UTF-8 字节数组
     *
     * @param obj 待序列化对象
     * @return 字节数组；入参为 null 时安全返回空数组
     */
    public static byte[] toJsonBytes(Object obj) {
        if (obj == null) {
            return new byte[0];
        }
        try {
            return defaultMapper.writeValueAsBytes(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 序列化字节数组失败: " + e.getMessage(), e);
        }
    }

    // ==================== 反序列化 (JSON -> Object) ====================

    /**
     * 将 JSON 字符串反序列化为指定类型的对象
     *
     * @param json  JSON 字符串
     * @param clazz 目标类型 Class
     * @param <T>   目标类型泛型
     * @return 反序列化对象；入参为空或空串时安全返回 null
     */
    public static <T> T parseObject(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Objects.requireNonNull(clazz, "目标类型 Class 不能为 null");
        try {
            return defaultMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 UTF-8 字节数组反序列化为指定类型的对象
     *
     * @param bytes JSON 字节数组
     * @param clazz 目标类型 Class
     * @param <T>   目标类型泛型
     * @return 反序列化对象；入参为空或长度为 0 时安全返回 null
     */
    public static <T> T parseObject(byte[] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        Objects.requireNonNull(clazz, "目标类型 Class 不能为 null");
        try {
            return defaultMapper.readValue(bytes, clazz);
        } catch (IOException e) {
            throw new IllegalArgumentException("JSON 字节数组反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 基于 TypeReference 将 JSON 字符串反序列化为复杂泛型对象
     *
     * @param json          JSON 字符串
     * @param typeReference 泛型类型引用
     * @param <T>           目标类型泛型
     * @return 反序列化对象；入参为空或空串时安全返回 null
     */
    public static <T> T parseObject(String json, TypeReference<T> typeReference) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Objects.requireNonNull(typeReference, "泛型类型引用 TypeReference 不能为 null");
        try {
            return defaultMapper.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 泛型反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 基于 TypeReference 将 UTF-8 字节数组反序列化为复杂泛型对象
     *
     * @param bytes         JSON 字节数组
     * @param typeReference 泛型类型引用
     * @param <T>           目标类型泛型
     * @return 反序列化对象；入参为空或长度为 0 时安全返回 null
     */
    public static <T> T parseObject(byte[] bytes, TypeReference<T> typeReference) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        Objects.requireNonNull(typeReference, "泛型类型引用 TypeReference 不能为 null");
        try {
            return defaultMapper.readValue(bytes, typeReference);
        } catch (IOException e) {
            throw new IllegalArgumentException("JSON 字节数组泛型反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 基于 Java Type 将 JSON 字符串反序列化为对象 (支持 ParameterizedType 等复杂泛型)
     *
     * @param json JSON 字符串
     * @param type 目标类型 Type
     * @param <T>  目标类型泛型
     * @return 反序列化对象；入参为空或空串时安全返回 null
     */
    public static <T> T parseObject(String json, java.lang.reflect.Type type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Objects.requireNonNull(type, "目标类型 Type 不能为 null");
        try {
            JavaType javaType = defaultMapper.constructType(type);
            return defaultMapper.readValue(json, javaType);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 基于 Java Type 将 UTF-8 字节数组反序列化为对象 (支持 ParameterizedType 等复杂泛型)
     *
     * @param bytes JSON 字节数组
     * @param type  目标类型 Type
     * @param <T>   目标类型泛型
     * @return 反序列化对象；入参为空或长度为 0 时安全返回 null
     */
    public static <T> T parseObject(byte[] bytes, java.lang.reflect.Type type) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        Objects.requireNonNull(type, "目标类型 Type 不能为 null");
        try {
            JavaType javaType = defaultMapper.constructType(type);
            return defaultMapper.readValue(bytes, javaType);
        } catch (IOException e) {
            throw new IllegalArgumentException("JSON 字节数组反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 JSON 数组字符串反序列化为 List 集合
     *
     * @param json  JSON 数组字符串
     * @param clazz 列表元素类型 Class
     * @param <T>   元素类型泛型
     * @return 反序列化列表；入参为空或空串时安全返回空列表（非 null）
     */
    public static <T> List<T> parseList(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        Objects.requireNonNull(clazz, "列表元素类型 Class 不能为 null");
        try {
            JavaType javaType = defaultMapper.getTypeFactory().constructCollectionType(List.class, clazz);
            return defaultMapper.readValue(json, javaType);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 反序列化列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 JSON 对象字符串反序列化为指定 Key/Value 类型的 Map
     *
     * @param json       JSON 对象字符串
     * @param keyClass   键类型 Class
     * @param valueClass 值类型 Class
     * @param <K>        键类型泛型
     * @param <V>        值类型泛型
     * @return 反序列化 Map；入参为空或空串时安全返回空 Map（非 null）
     */
    public static <K, V> Map<K, V> parseMap(String json, Class<K> keyClass, Class<V> valueClass) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        Objects.requireNonNull(keyClass, "Map 键类型 Class 不能为 null");
        Objects.requireNonNull(valueClass, "Map 值类型 Class 不能为 null");
        try {
            JavaType javaType = defaultMapper.getTypeFactory().constructMapType(Map.class, keyClass, valueClass);
            return defaultMapper.readValue(json, javaType);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 反序列化 Map 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 JSON 对象字符串反序列化为 Map&lt;String, Object&gt;
     *
     * @param json JSON 对象字符串
     * @return 反序列化 Map；入参为空或空串时安全返回空 Map（非 null）
     */
    public static Map<String, Object> parseMap(String json) {
        return parseMap(json, String.class, Object.class);
    }

    /**
     * 将 JSON 字符串解析为树形结构的 JsonNode
     *
     * @param json JSON 字符串
     * @return JsonNode；入参为空或空串时安全返回 null
     */
    public static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return defaultMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 读取树节点失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将字节数组解析为树形结构的 JsonNode
     *
     * @param bytes JSON 字节数组
     * @return JsonNode；入参为空或长度为 0 时安全返回 null
     */
    public static JsonNode readTree(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return defaultMapper.readTree(bytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("JSON 读取树节点失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从输入流解析为树形结构的 JsonNode
     *
     * @param inputStream JSON 输入流
     * @return JsonNode；入参为 null 时安全返回 null
     */
    public static JsonNode readTree(InputStream inputStream) {
        if (inputStream == null) {
            return null;
        }
        try {
            return defaultMapper.readTree(inputStream);
        } catch (IOException e) {
            throw new IllegalArgumentException("JSON 读取树节点失败: " + e.getMessage(), e);
        }
    }

    /**
     * 判断给定字符串是否为合法的 JSON 格式（支持 JSON Object、JSON Array 或基础标量）
     *
     * @param json 待校验字符串
     * @return 若是合法 JSON 则返回 true；若为 null、空白串或非法 JSON 格式则返回 false
     */
    public static boolean isValidJson(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            defaultMapper.readTree(json);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 将任意源对象转换为目标类型（类似反射式属性映射或动态结构重塑）
     *
     * @param fromValue   原始源对象
     * @param toValueType 目标类型 Class
     * @param <T>         目标类型泛型
     * @return 转换后的目标对象；源对象为 null 时安全返回 null
     */
    public static <T> T convertValue(Object fromValue, Class<T> toValueType) {
        if (fromValue == null) {
            return null;
        }
        Objects.requireNonNull(toValueType, "目标类型 Class 不能为 null");
        return defaultMapper.convertValue(fromValue, toValueType);
    }

    /**
     * 基于 TypeReference 将任意源对象转换为目标复杂泛型对象
     *
     * @param fromValue      原始源对象
     * @param toValueTypeRef 目标泛型类型引用
     * @param <T>            目标类型泛型
     * @return 转换后的目标对象；源对象为 null 时安全返回 null
     */
    public static <T> T convertValue(Object fromValue, TypeReference<T> toValueTypeRef) {
        if (fromValue == null) {
            return null;
        }
        Objects.requireNonNull(toValueTypeRef, "目标泛型类型引用 TypeReference 不能为 null");
        return defaultMapper.convertValue(fromValue, toValueTypeRef);
    }

    /**
     * 基于 Jackson 内存树机制对源对象执行类型安全的纯内存深度克隆
     * 避免了生成 JSON 字符串的中转开销，自动继承东八区与 JSR-310 时间格式支持，实现绝对内存隔离防并发脏写
     * 支持泛型容器（List、Set、Map）及不可变集合，递归深拷贝每个元素防类型退化与构造崩溃
     *
     * @param source 待克隆源对象
     * @param <T>    对象类型泛型
     * @return 深度克隆后的全新对象；入参为 null 时安全返回 null
     */
    @SuppressWarnings("unchecked")
    public static <T> T deepClone(T source) {
        if (source == null) {
            return null;
        }
        try {
            if (source instanceof List<?> list) {
                List<Object> copy = new ArrayList<>(list.size());
                for (Object item : list) {
                    copy.add(deepClone(item));
                }
                return (T) copy;
            }
            if (source instanceof Set<?> set) {
                Set<Object> copy = new LinkedHashSet<>(set.size());
                for (Object item : set) {
                    copy.add(deepClone(item));
                }
                return (T) copy;
            }
            if (source instanceof Map<?, ?> map) {
                Map<Object, Object> copy = new LinkedHashMap<>(map.size());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    copy.put(deepClone(entry.getKey()), deepClone(entry.getValue()));
                }
                return (T) copy;
            }
            JsonNode node = cloneMapper.valueToTree(source);
            return (T) cloneMapper.treeToValue(node, source.getClass());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("对象深度克隆失败: " + e.getMessage(), e);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException iae && iae.getMessage() != null && iae.getMessage().startsWith("对象深度克隆失败")) {
                throw iae;
            }
            throw new IllegalArgumentException("对象深度克隆失败: " + e.getMessage(), e);
        }
    }

    /**
     * 基于 TypeReference 对复杂泛型容器（如 List&lt;T&gt;、Map&lt;K, V&gt;）执行类型安全的纯内存深度克隆
     *
     * @param source         待克隆源对象
     * @param toValueTypeRef 目标泛型类型引用
     * @param <T>            对象类型泛型
     * @return 深度克隆后的全新对象；入参为 null 时安全返回 null
     */
    public static <T> T deepClone(T source, TypeReference<T> toValueTypeRef) {
        if (source == null) {
            return null;
        }
        Objects.requireNonNull(toValueTypeRef, "目标泛型类型引用 TypeReference 不能为 null");
        return cloneMapper.convertValue(source, toValueTypeRef);
    }
}
