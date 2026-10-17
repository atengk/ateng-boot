package io.github.atengk.boot.core.model;

import java.io.Serializable;

/**
 * 通用三元组不可变载体（基于 JDK 17 Record 原生实现）
 * 适用于跨层多维度属性返回、范围区间三要素（如起始-中介-截止）与轻量多元容器场景
 *
 * @param <L> 左值类型
 * @param <M> 中间值类型
 * @param <R> 右值类型
 * @param left   左值元素
 * @param middle 中间值元素
 * @param right  右值元素
 * @author Ateng
 * @since 2026-10-17
 */
public record Triple<L, M, R>(L left, M middle, R right) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 静态工厂便捷构造方法
     *
     * @param left   左值元素
     * @param middle 中间值元素
     * @param right  右值元素
     * @param <L>    左值类型
     * @param <M>    中间值类型
     * @param <R>    右值类型
     * @return 新建的不可变 Triple 实例
     */
    public static <L, M, R> Triple<L, M, R> of(L left, M middle, R right) {
        return new Triple<>(left, middle, right);
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
     * 获取中间值（JavaBean 风格别名）
     *
     * @return 中间值元素
     */
    public M getMiddle() {
        return middle;
    }

    /**
     * 获取右值（JavaBean 风格别名）
     *
     * @return 右值元素
     */
    public R getRight() {
        return right;
    }
}
