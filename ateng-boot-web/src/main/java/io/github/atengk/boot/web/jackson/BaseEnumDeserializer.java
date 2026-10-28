package io.github.atengk.boot.web.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.util.EnumUtil;
import io.github.atengk.boot.core.util.StringUtil;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 强类型通用枚举 Jackson 双向反序列化器
 * 支持将数值 code、字符串 code 以及枚举常量名称（大小写不敏感）精准反序列化为目标枚举实例
 *
 * @param <T> 枚举目标类型
 * @author Ateng
 * @since 2026-10-28
 */
public class BaseEnumDeserializer<T> extends JsonDeserializer<T> implements ContextualDeserializer {

    private final Class<?> enumClass;
    private final Map<String, Object> nameMap;

    public BaseEnumDeserializer() {
        this(null);
    }

    public BaseEnumDeserializer(Class<?> enumClass) {
        this.enumClass = resolveTargetEnumClass(enumClass);
        if (this.enumClass != null && this.enumClass.isEnum()) {
            Object[] constants = this.enumClass.getEnumConstants();
            Map<String, Object> map = new HashMap<>(constants.length * 3);
            for (Object constant : constants) {
                if (constant instanceof Enum<?> e) {
                    map.put(e.name(), constant);
                    map.putIfAbsent(e.name().toUpperCase(Locale.ROOT), constant);
                    map.putIfAbsent(e.name().toLowerCase(Locale.ROOT), constant);
                }
            }
            this.nameMap = Collections.unmodifiableMap(map);
        } else {
            this.nameMap = Collections.emptyMap();
        }
    }

    /**
     * 解析目标枚举类，兼容常量子类（带抽象方法或独立常量体的枚举）
     */
    static Class<?> resolveTargetEnumClass(Class<?> clazz) {
        if (clazz == null) {
            return null;
        }
        if (clazz.isEnum()) {
            return clazz;
        }
        if (clazz.getSuperclass() != null && clazz.getSuperclass().isEnum()) {
            return clazz.getSuperclass();
        }
        return clazz;
    }

    @SuppressWarnings("unchecked")
    @Override
    public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (enumClass == null) {
            return null;
        }

        JsonToken currentToken = p.currentToken();
        if (currentToken == JsonToken.VALUE_NULL) {
            return null;
        }

        // 1. 处理数值型入参（如 1, 1001）
        if (currentToken == JsonToken.VALUE_NUMBER_INT) {
            Number number = p.getNumberValue();
            return (T) matchByNumber(number, ctxt);
        }

        // 2. 处理字符串型入参（如 "1", "ACTIVE", "active"）
        if (currentToken == JsonToken.VALUE_STRING) {
            String text = p.getText();
            return (T) matchByString(text, ctxt);
        }

        // 3. 其他非预期 JSON Token 抛出异常
        return (T) ctxt.handleUnexpectedToken(enumClass, p);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object matchByNumber(Number number, DeserializationContext ctxt) throws IOException {
        // 优先以原始 Number 对象查询
        Optional<?> matched = EnumUtil.fromCode((Class) enumClass, (Serializable) number);
        if (matched.isPresent()) {
            return matched.get();
        }

        // 尝试转换为 Integer 或 Long 再次比对（防御泛型类型擦除后 Long vs Integer 判等失效）
        if (number instanceof Long l) {
            matched = EnumUtil.fromCode((Class) enumClass, l.intValue());
        } else if (number instanceof Integer i) {
            matched = EnumUtil.fromCode((Class) enumClass, i.longValue());
        }
        if (matched.isPresent()) {
            return matched.get();
        }

        // 尝试以字符串形态查询（部分枚举 code 为 String，如 "1"）
        matched = EnumUtil.fromCode((Class) enumClass, String.valueOf(number));
        if (matched.isPresent()) {
            return matched.get();
        }

        // 未命中任何有效枚举，抛出清晰反序列化异常
        return ctxt.handleWeirdNumberValue(
                enumClass,
                number,
                "无法将数值 [%s] 转换为枚举类型 [%s]，有效编码列表为: %s",
                number,
                enumClass.getName(),
                EnumUtil.getAllCodes((Class) enumClass)
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object matchByString(String text, DeserializationContext ctxt) throws IOException {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();

        // 1. 优先尝试以字符串本身作为 code 匹配（如 String 类型 code）
        Optional<?> matched = EnumUtil.fromCode((Class) enumClass, text);
        if (matched.isPresent()) {
            return matched.get();
        }
        if (!text.equals(trimmed)) {
            matched = EnumUtil.fromCode((Class) enumClass, trimmed);
            if (matched.isPresent()) {
                return matched.get();
            }
        }

        // 2. 若入参全为纯数字，尝试按 Long / Integer 解析后匹配
        if (isDigits(trimmed)) {
            try {
                long longVal = Long.parseLong(trimmed);
                matched = EnumUtil.fromCode((Class) enumClass, (int) longVal);
                if (matched.isEmpty()) {
                    matched = EnumUtil.fromCode((Class) enumClass, longVal);
                }
                if (matched.isPresent()) {
                    return matched.get();
                }
            } catch (NumberFormatException ignored) {
                // 超出 Long 范围，继续向下尝试匹配常量名
            }
        }

        // 3. 尝试按枚举常量名匹配（支持原名及忽略大小写）
        Object constant = nameMap.get(trimmed);
        if (constant != null) {
            return constant;
        }

        // 4. 空字符串保底处理：若枚举未定义空字符串 code，则按 null 优雅处理
        if (trimmed.isEmpty()) {
            return null;
        }

        // 5. 未命中任何有效枚举，抛出清晰反序列化异常
        return ctxt.handleWeirdStringValue(
                enumClass,
                text,
                "无法将字符串值 [%s] 转换为枚举类型 [%s]，有效编码列表为: %s",
                text,
                enumClass.getName(),
                EnumUtil.getAllCodes((Class) enumClass)
        );
    }

    private static boolean isDigits(String str) {
        if (!StringUtil.hasText(str)) {
            return false;
        }
        int start = (str.charAt(0) == '-' || str.charAt(0) == '+') ? 1 : 0;
        if (start >= str.length()) {
            return false;
        }
        for (int i = start; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
        JavaType type = null;
        if (property != null) {
            type = property.getType();
        } else if (ctxt.getContextualType() != null) {
            type = ctxt.getContextualType();
        }
        if (type != null) {
            Class<?> rawClass = resolveTargetEnumClass(type.getRawClass());
            if (rawClass != null && IBaseEnum.class.isAssignableFrom(rawClass)) {
                return new BaseEnumDeserializer<>(rawClass);
            }
        }
        return this;
    }
}
