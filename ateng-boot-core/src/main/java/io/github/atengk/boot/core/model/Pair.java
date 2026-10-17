package io.github.atengk.boot.core.model;

import java.io.Serializable;

/**
 * 通用双元组不可变载体（基于 JDK 17 Record 原生实现）
 * 适用于方法多值返回、映射对关联与轻量二元容器场景，严格保障线程安全与值对象语义
 *
 * @param <L> 左值类型
 * @param <R> 右值类型
 * @param left  左值元素
 * @param right 右值元素
 * @author Ateng
 * @since 2026-10-17
 */
public record Pair<L, R>(L left, R right) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 静态工厂便捷构造方法
     *
     * @param left  左值元素
     * @param right 右值元素
     * @param <L>   左值类型
     * @param <R>   右值类型
     * @return 新建的不可变 Pair 实例
     */
    public static <L, R> Pair<L, R> of(L left, R right) {
        return new Pair<>(left, right);
    }

    /**
     * 获取左值（JavaBean 风格别名）
     *
     * @return 左值元素
     */
    public L getLeft() {
        return left;
    }

    /**
     * 获取右值（JavaBean 风格别名）
     *
     * @return 右值元素
     */
    public R getRight() {
        return right;
    }

    /**
     * 获取键（Map.Entry 风格别名）
     *
     * @return 键元素（即左值）
     */
    public L getKey() {
        return left;
    }

    /**
     * 获取值（Map.Entry 风格别名）
     *
     * @return 值元素（即右值）
     */
    public R getValue() {
        return right;
    }
}
