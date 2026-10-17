package io.github.atengk.boot.core.util;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

import java.beans.PropertyDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * JavaBean 属性拷贝与转换增强工具
 * 基于 Spring BeanUtils 原生能力，提供类型安全的自动实例化拷贝、批量集合转换与非空覆盖
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class BeanUtil {

    private BeanUtil() {
    }

    /**
     * 将源对象属性拷贝至新实例化的目标类型对象中
     *
     * @param source      源数据对象
     * @param targetClass 目标类型 Class（必须具备公有无参构造方法）
     * @param <T>         目标泛型
     * @return 目标实例；当 source 为 null 时直接返回 null（空安全）
     */
    public static <T> T copy(Object source, Class<T> targetClass) {
        if (source == null) {
            return null;
        }
        Objects.requireNonNull(targetClass, "targetClass 不能为空");
        try {
            T target = targetClass.getDeclaredConstructor().newInstance();
            BeanUtils.copyProperties(source, target);
            return target;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法通过反射实例化目标类: " + targetClass.getName(), e);
        }
    }

    /**
     * 将源对象集合批量拷贝为目标类型的新列表
     *
     * @param sourceList  源对象集合
     * @param targetClass 目标类型 Class
     * @param <T>         目标泛型
     * @return 目标类型列表；源集合为空或 null 时返回不可变空集合
     */
    public static <T> List<T> copyList(Collection<?> sourceList, Class<T> targetClass) {
        if (sourceList == null || sourceList.isEmpty()) {
            return Collections.emptyList();
        }
        Objects.requireNonNull(targetClass, "targetClass 不能为空");
        List<T> resultList = new ArrayList<>(sourceList.size());
        for (Object source : sourceList) {
            if (source != null) {
                resultList.add(copy(source, targetClass));
            }
        }
        return resultList;
    }

    /**
     * 属性拷贝并忽略源对象中的 null 值（仅用源对象的非空属性覆盖目标对象）
     * 适用于局部更新、Patch 补丁合并等业务场景
     *
     * @param source 源数据对象
     * @param target 目标数据对象
     */
    public static void copyIgnoreNull(Object source, Object target) {
        if (source == null || target == null) {
            return;
        }
        String[] nullProperties = getNullPropertyNames(source);
        BeanUtils.copyProperties(source, target, nullProperties);
    }

    /**
     * 提取指定对象中所有值为 null 的 JavaBean 属性名数组
     *
     * @param source 目标对象
     * @return 值为 null 的属性名数组
     */
    public static String[] getNullPropertyNames(Object source) {
        if (source == null) {
            return new String[0];
        }
        BeanWrapper wrapper = new BeanWrapperImpl(source);
        PropertyDescriptor[] descriptors = wrapper.getPropertyDescriptors();

        Set<String> emptyNames = new HashSet<>();
        for (PropertyDescriptor pd : descriptors) {
            String propertyName = pd.getName();
            if (wrapper.isReadableProperty(propertyName)) {
                try {
                    Object value = wrapper.getPropertyValue(propertyName);
                    if (value == null) {
                        emptyNames.add(propertyName);
                    }
                } catch (Exception ignored) {
                    // 忽略不可读或异常字段
                }
            }
        }
        return emptyNames.toArray(new String[0]);
    }
}
