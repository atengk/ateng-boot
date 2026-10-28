package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.OptionItem;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 强类型通用枚举高速解析与选项转换工具门面
 * 基于并发安全内存缓存实现 O(1) 状态码查找、合法性校验、选项模型映射与多重格式兼容
 *
 * @author Ateng
 * @since 2026-10-28
 */
public final class EnumUtil {

    private static final Map<Class<?>, EnumMetadata<?, ?>> METADATA_CACHE = new ConcurrentHashMap<>(64);

    private EnumUtil() {
    }

    /**
     * 根据编码值查找对应的枚举实例
     *
     * @param clazz 枚举类 Class
     * @param code  状态码
     * @param <E>   枚举类型
     * @param <T>   状态码类型
     * @return 包含枚举实例的 Optional，未找到时返回 Optional.empty()
     */
    public static <E extends Enum<E> & IBaseEnum<T>, T extends Serializable> Optional<E> fromCode(Class<E> clazz, T code) {
        if (clazz == null || code == null) {
            return Optional.empty();
        }
        EnumMetadata<?, ?> metadata = getMetadata(clazz);
        if (metadata == null) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        E value = (E) metadata.find(code);
        return Optional.ofNullable(value);
    }

    /**
     * 根据编码值获取枚举实例，未找到时抛出 BAD_REQUEST 业务异常
     *
     * @param clazz 枚举类 Class
     * @param code  状态码
     * @param <E>   枚举类型
     * @param <T>   状态码类型
     * @return 匹配的枚举实例
     * @throws BusinessException 未匹配时抛出
     */
    public static <E extends Enum<E> & IBaseEnum<T>, T extends Serializable> E getByCodeOrThrow(Class<E> clazz, T code) {
        return fromCode(clazz, code).orElseThrow(() -> new BusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                String.format("未找到编码为 [%s] 的枚举常量 [%s]", code, clazz != null ? clazz.getSimpleName() : "null")));
    }

    /**
     * 根据编码值获取枚举实例，未找到时抛出携带自定义错误提示的业务异常
     *
     * @param clazz   枚举类 Class
     * @param code    状态码
     * @param message 自定义异常提示文案
     * @param <E>     枚举类型
     * @param <T>     状态码类型
     * @return 匹配的枚举实例
     * @throws BusinessException 未匹配时抛出
     */
    public static <E extends Enum<E> & IBaseEnum<T>, T extends Serializable> E getByCodeOrThrow(Class<E> clazz, T code, String message) {
        return fromCode(clazz, code).orElseThrow(() -> new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), message));
    }

    /**
     * 根据编码值获取枚举实例，未找到时抛出指定状态码契约的业务异常
     *
     * @param clazz      枚举类 Class
     * @param code       状态码
     * @param resultCode 业务错误码契约
     * @param <E>        枚举类型
     * @param <T>        状态码类型
     * @return 匹配的枚举实例
     * @throws BusinessException 未匹配时抛出
     */
    public static <E extends Enum<E> & IBaseEnum<T>, T extends Serializable> E getByCodeOrThrow(Class<E> clazz, T code, IResultCode resultCode) {
        return fromCode(clazz, code).orElseThrow(() -> new BusinessException(resultCode));
    }

    /**
     * 根据编码获取对应的中文展示标签
     *
     * @param clazz 枚举类 Class
     * @param code  状态码
     * @param <E>   枚举类型
     * @param <T>   状态码类型
     * @return 展示标签，未找到时返回 null
     */
    public static <E extends Enum<E> & IBaseEnum<T>, T extends Serializable> String getLabelByCode(Class<E> clazz, T code) {
        return getLabelByCode(clazz, code, null);
    }

    /**
     * 根据编码获取对应的中文展示标签，支持默认保底文案
     *
     * @param clazz        枚举类 Class
     * @param code         状态码
     * @param defaultLabel 未找到时的保底文案
     * @param <E>          枚举类型
     * @param <T>          状态码类型
     * @return 展示标签或保底文案
     */
    public static <E extends Enum<E> & IBaseEnum<T>, T extends Serializable> String getLabelByCode(Class<E> clazz, T code, String defaultLabel) {
        return fromCode(clazz, code)
                .map(IBaseEnum::getLabel)
                .orElse(defaultLabel);
    }

    /**
     * 判断给定的编码在目标枚举中是否合法有效
     *
     * @param clazz 枚举类 Class
     * @param code  状态码对象
     * @return 是否合法
     */
    public static boolean isValidCode(Class<?> clazz, Object code) {
        if (clazz == null || code == null || !clazz.isEnum() || !IBaseEnum.class.isAssignableFrom(clazz)) {
            return false;
        }
        EnumMetadata<?, ?> metadata = getMetadata(clazz);
        return metadata != null && metadata.contains(code);
    }

    /**
     * 强类型判断给定的编码在目标枚举中是否合法有效
     *
     * @param clazz 枚举类 Class
     * @param code  状态码
     * @param <E>   枚举类型
     * @param <T>   状态码类型
     * @return 是否合法
     */
    public static <E extends Enum<E> & IBaseEnum<T>, T extends Serializable> boolean isValidCode(Class<E> clazz, T code) {
        return isValidCode((Class<?>) clazz, (Object) code);
    }

    /**
     * 将枚举类中的所有常量转换为标准前端选项模型列表
     *
     * @param clazz 枚举类 Class
     * @param <E>   枚举类型
     * @param <T>   状态码类型
     * @return 不可变的 OptionItem 列表，未找到或无效时返回空集合
     */
    @SuppressWarnings("unchecked")
    public static <E extends Enum<E> & IBaseEnum<T>, T extends Serializable> List<OptionItem<T>> toOptions(Class<E> clazz) {
        if (clazz == null || !clazz.isEnum() || !IBaseEnum.class.isAssignableFrom(clazz)) {
            return Collections.emptyList();
        }
        EnumMetadata<?, ?> metadata = getMetadata(clazz);
        if (metadata == null) {
            return Collections.emptyList();
        }
        return (List<OptionItem<T>>) (List<?>) metadata.options();
    }

    /**
     * 无泛型强约束地将实现了 IBaseEnum 的枚举类转换为通用选项模型列表
     *
     * @param clazz 枚举类 Class
     * @return 不可变的 OptionItem 列表
     */
    @SuppressWarnings("unchecked")
    public static List<OptionItem<Object>> toOptionsUntyped(Class<?> clazz) {
        if (clazz == null || !clazz.isEnum() || !IBaseEnum.class.isAssignableFrom(clazz)) {
            return Collections.emptyList();
        }
        EnumMetadata<?, ?> metadata = getMetadata(clazz);
        if (metadata == null) {
            return Collections.emptyList();
        }
        return (List<OptionItem<Object>>) (List<?>) metadata.options();
    }

    /**
     * 获取枚举类中定义的所有编码集合
     *
     * @param clazz 枚举类 Class
     * @param <E>   枚举类型
     * @param <T>   状态码类型
     * @return 不可变的编码列表
     */
    @SuppressWarnings("unchecked")
    public static <E extends Enum<E> & IBaseEnum<T>, T extends Serializable> List<T> getAllCodes(Class<E> clazz) {
        if (clazz == null || !clazz.isEnum() || !IBaseEnum.class.isAssignableFrom(clazz)) {
            return Collections.emptyList();
        }
        EnumMetadata<?, ?> metadata = getMetadata(clazz);
        if (metadata == null) {
            return Collections.emptyList();
        }
        return (List<T>) metadata.allCodes();
    }

    /**
     * 清理所有枚举元数据缓存（主要用于热卸载或测试重置）
     */
    public static void clearCache() {
        METADATA_CACHE.clear();
    }

    private static EnumMetadata<?, ?> getMetadata(Class<?> clazz) {
        return METADATA_CACHE.computeIfAbsent(clazz, EnumUtil::buildMetadata);
    }

    private static EnumMetadata<?, ?> buildMetadata(Class<?> clazz) {
        if (!clazz.isEnum() || !IBaseEnum.class.isAssignableFrom(clazz)) {
            return null;
        }
        Object[] enumConstants = clazz.getEnumConstants();
        if (enumConstants == null || enumConstants.length == 0) {
            return new EnumMetadata<>(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
        }

        Map<Object, Object> codeMap = new HashMap<>(enumConstants.length);
        Map<String, Object> stringCodeMap = new HashMap<>(enumConstants.length);
        List<OptionItem<Object>> options = new ArrayList<>(enumConstants.length);
        List<Object> allCodes = new ArrayList<>(enumConstants.length);

        for (Object constant : enumConstants) {
            if (constant instanceof IBaseEnum<?> baseEnum) {
                Object code = baseEnum.getCode();
                codeMap.put(code, constant);
                if (code != null) {
                    stringCodeMap.put(String.valueOf(code), constant);
                }
                @SuppressWarnings("unchecked")
                OptionItem<Object> option = (OptionItem<Object>) baseEnum.toOption();
                options.add(option);
                allCodes.add(code);
            }
        }

        return new EnumMetadata<>(
                Collections.unmodifiableMap(codeMap),
                Collections.unmodifiableMap(stringCodeMap),
                Collections.unmodifiableList(options),
                Collections.unmodifiableList(allCodes)
        );
    }

    private record EnumMetadata<E, T>(
            Map<Object, E> codeMap,
            Map<String, E> stringCodeMap,
            List<OptionItem<T>> options,
            List<T> allCodes
    ) {
        public E find(Object code) {
            E val = codeMap.get(code);
            if (val != null) {
                return val;
            }
            return stringCodeMap.get(String.valueOf(code));
        }

        public boolean contains(Object code) {
            return find(code) != null;
        }
    }
}
