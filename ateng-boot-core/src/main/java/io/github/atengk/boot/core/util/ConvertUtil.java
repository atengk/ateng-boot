package io.github.atengk.boot.core.util;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 空安全与抗异常通用类型转换门面工具类
 * 防御任意非法类型格式与空指针输入，统一通过兜底值机制实现平滑类型转换，杜绝 ClassCastException 与 NumberFormatException
 *
 * @author Ateng
 * @since 2026-10-26
 */
public final class ConvertUtil {

    private ConvertUtil() {
    }

    /**
     * 将对象安全转换为 Long，转换失败或输入为空时返回默认值
     *
     * @param value        待转换对象（支持 Number、String 等）
     * @param defaultValue 转换失败时的保底默认值
     * @return 转换后的 Long 值或默认值
     */
    public static Long toLong(Object value, Long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        String str = value.toString().trim();
        if (str.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException ignored) {
            try {
                return new BigDecimal(str).longValue();
            } catch (Exception ex) {
                return defaultValue;
            }
        }
    }

    /**
     * 将对象安全转换为 Long，转换失败或输入为空时返回 null
     *
     * @param value 待转换对象
     * @return Long 值或 null
     */
    public static Long toLong(Object value) {
        return toLong(value, null);
    }

    /**
     * 将对象安全转换为 Integer，转换失败或输入为空时返回默认值
     *
     * @param value        待转换对象
     * @param defaultValue 保底默认值
     * @return Integer 值或默认值
     */
    public static Integer toInt(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        String str = value.toString().trim();
        if (str.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException ignored) {
            try {
                return new BigDecimal(str).intValue();
            } catch (Exception ex) {
                return defaultValue;
            }
        }
    }

    /**
     * 将对象安全转换为 Integer，转换失败或输入为空时返回 null
     *
     * @param value 待转换对象
     * @return Integer 值或 null
     */
    public static Integer toInt(Object value) {
        return toInt(value, null);
    }

    /**
     * 将对象安全转换为 Double，转换失败或输入为空时返回默认值
     *
     * @param value        待转换对象
     * @param defaultValue 保底默认值
     * @return Double 值或默认值
     */
    public static Double toDouble(Object value, Double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        String str = value.toString().trim();
        if (str.isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /**
     * 将对象安全转换为 Double，转换失败或输入为空时返回 null
     *
     * @param value 待转换对象
     * @return Double 值或 null
     */
    public static Double toDouble(Object value) {
        return toDouble(value, null);
    }

    /**
     * 将对象安全转换为 BigDecimal，转换失败或输入为空时返回默认值
     *
     * @param value        待转换对象
     * @param defaultValue 保底默认值
     * @return BigDecimal 值或默认值
     */
    public static BigDecimal toBigDecimal(Object value, BigDecimal defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        String str = value.toString().trim();
        if (str.isEmpty()) {
            return defaultValue;
        }
        try {
            return new BigDecimal(str);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    /**
     * 将对象安全转换为 BigDecimal，转换失败或输入为空时返回 null
     *
     * @param value 待转换对象
     * @return BigDecimal 值或 null
     */
    public static BigDecimal toBigDecimal(Object value) {
        return toBigDecimal(value, null);
    }

    /**
     * 将对象安全转换为 Boolean，支持 boolean/1/0/yes/no/true/false 智能解析
     *
     * @param value        待转换对象
     * @param defaultValue 保底默认值
     * @return Boolean 值或默认值
     */
    public static Boolean toBoolean(Object value, Boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        String str = value.toString().trim().toLowerCase();
        if (str.isEmpty()) {
            return defaultValue;
        }
        return switch (str) {
            case "true", "1", "yes", "y", "t", "ok" -> Boolean.TRUE;
            case "false", "0", "no", "n", "f" -> Boolean.FALSE;
            default -> defaultValue;
        };
    }

    /**
     * 将对象安全转换为 Boolean，转换失败或输入为空时返回 null
     *
     * @param value 待转换对象
     * @return Boolean 值或 null
     */
    public static Boolean toBoolean(Object value) {
        return toBoolean(value, null);
    }

    /**
     * 将对象安全转换为 String，转换失败或输入为空时返回默认值
     *
     * @param value        待转换对象
     * @param defaultValue 保底默认值
     * @return 字符串表示或默认值
     */
    public static String toStr(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }

    /**
     * 将对象安全转换为 String，输入为 null 时返回 null
     *
     * @param value 待转换对象
     * @return 字符串或 null
     */
    public static String toStr(Object value) {
        return toStr(value, null);
    }

    /**
     * 将异构对象安全转换为通用 Object List 集合（{@link #toList(Object, Class)} 的泛型通用重载）
     * 支持 Iterable（含 Collection）、Iterator、Array 与单个对象包装；若为 null 则保底返回空列表
     *
     * @param value 待转换对象
     * @return 转换后的元素列表，永不返回 null
     */
    public static List<Object> toList(Object value) {
        return toList(value, Object.class);
    }

    /**
     * 将异构对象安全转换为 List 集合
     * 支持 Iterable（含 Collection）、Iterator、Array 与单个对象包装；若为 null 则保底返回空列表
     *
     * @param value       待转换对象
     * @param elementType 目标元素类型
     * @param <T>         元素泛型
     * @return 转换后的元素列表，永不返回 null
     */
    public static <T> List<T> toList(Object value, Class<T> elementType) {
        if (value == null) {
            return Collections.emptyList();
        }
        List<T> result = new ArrayList<>();
        collectElements(value, elementType, result);
        return result;
    }

    /**
     * 将异构对象安全转换为通用 Object Set 集合（{@link #toSet(Object, Class)} 的泛型通用重载）
     * 支持 Iterable（含 Collection）、Iterator、Array 与单个对象包装；保持插入顺序去重；
     * 若为 null、空白字符序列或过滤后无匹配元素，保底返回不可变空 Set
     *
     * @param value 待转换对象
     * @return 转换后的保序去重集合，永不返回 null
     */
    public static Set<Object> toSet(Object value) {
        return toSet(value, Object.class);
    }

    /**
     * 将异构对象安全转换为保序去重 Set 集合
     * 支持 Iterable（含 Collection）、Iterator、Array 与单个对象包装；
     * 若为 null、空白字符序列或过滤后无匹配元素，保底返回不可变空 Set
     *
     * @param value       待转换对象
     * @param elementType 目标元素类型
     * @param <T>         元素泛型
     * @return 转换后的保序去重集合（非空时为 {@link LinkedHashSet}），永不返回 null
     */
    public static <T> Set<T> toSet(Object value, Class<T> elementType) {
        if (value == null || (value instanceof CharSequence cs && cs.toString().isBlank())) {
            return Collections.emptySet();
        }
        Set<T> result = new LinkedHashSet<>();
        collectElements(value, elementType, result);
        return result.isEmpty() ? Collections.emptySet() : result;
    }

    private static <T> void collectElements(Object value, Class<T> elementType, Collection<T> target) {
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addIfMatches(item, elementType, target);
            }
            return;
        }
        if (value instanceof Iterator<?> iterator) {
            while (iterator.hasNext()) {
                addIfMatches(iterator.next(), elementType, target);
            }
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                addIfMatches(Array.get(value, i), elementType, target);
            }
            return;
        }
        addIfMatches(value, elementType, target);
    }

    @SuppressWarnings("unchecked")
    private static <T> void addIfMatches(Object item, Class<T> elementType, Collection<T> target) {
        if (item != null && (elementType == null || elementType.isInstance(item))) {
            target.add((T) item);
        }
    }
}
